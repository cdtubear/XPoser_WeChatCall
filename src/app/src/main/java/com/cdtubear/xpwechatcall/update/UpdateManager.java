package com.cdtubear.xpwechatcall.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import com.cdtubear.xpwechatcall.BuildConfig;
import com.zgcwkj.comm.LogHelp;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 版本更新
 *
 * 从 GitHub Releases 读取最新版本，比较版本号后下载 APK。
 * 下载完成后校验包名、版本与签名，再交给系统安装器，避免装错包或被替换包。
 *
 * 所有网络与文件操作都在后台线程执行，回调也在后台线程触发，
 * 调用方需要自行切回主线程更新界面。
 */
public final class UpdateManager {

    private static final String TAG = "XpWeChatCall";

    /** 更新来源仓库 */
    public static final String REPO = "cdtubear/XPoser_WeChatCall";

    /** 发布页地址，打不开 API 时可以让用户手动下载 */
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";

    private static final String API_LATEST =
        "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static final String PREFS = "update_state";
    private static final String K_LATEST = "latest_json";
    private static final String K_CHECKED_AT = "checked_at";
    private static final String K_APK_PATH = "apk_path";

    /** 单个响应最大字节数，避免异常响应占满内存 */
    private static final int MAX_RESPONSE = 512 * 1024;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "xp-update");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean CHECKING = new AtomicBoolean();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean();

    /** 当前下载进度，未知总量时为 -1 */
    private static volatile int sProgress = -1;

    /** 检查更新的回调 */
    public interface CheckCallback {
        void onSuccess(UpdateInfo latest, boolean hasUpdate);

        void onError(String message);
    }

    /** 下载安装包的回调 */
    public interface DownloadCallback {
        void onProgress(int percent, long downloaded, long total);

        void onSuccess(File apk);

        void onError(String message);
    }

    private UpdateManager() {
    }

    /**
     * 当前安装版本号
     */
    public static String currentVersion(Context context) {
        var name = BuildConfig.VERSION_NAME;
        return name == null ? "" : name;
    }

    /**
     * 是否正在检查更新
     */
    public static boolean isChecking() {
        return CHECKING.get();
    }

    /**
     * 是否正在下载
     */
    public static boolean isDownloading() {
        return DOWNLOADING.get();
    }

    /**
     * 当前下载进度百分比，未知总量时为 -1
     */
    public static int progress() {
        return sProgress;
    }

    /**
     * 上次成功检查的时间，未检查过返回 0
     */
    public static long checkedAt(Context context) {
        return prefs(context).getLong(K_CHECKED_AT, 0L);
    }

    /**
     * 本地缓存的最近一次 Release 信息，没有则返回 null
     */
    public static UpdateInfo latest(Context context) {
        var raw = prefs(context).getString(K_LATEST, null);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UpdateInfo.fromJson(new JSONObject(raw));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 缓存的版本是否比当前安装版本新
     */
    public static boolean hasNewer(Context context) {
        var latest = latest(context);
        return latest != null && compare(latest.version, currentVersion(context)) > 0;
    }

    /**
     * 已经下载完成且仍可用于升级的安装包，没有则返回 null
     *
     * 安装成功后旧包版本不会高于当前版本，这里顺手清掉，避免下次进来还提示安装。
     */
    public static File downloadedApk(Context context) {
        var prefs = prefs(context);
        var path = prefs.getString(K_APK_PATH, "");
        if (path == null || path.isEmpty()) {
            return null;
        }
        var file = new File(path);
        if (!file.isFile() || file.length() <= 0 || !file.getName().endsWith(".apk")) {
            prefs.edit().remove(K_APK_PATH).apply();
            return null;
        }
        PackageInfo archive = null;
        try {
            archive = context.getPackageManager()
                .getPackageArchiveInfo(file.getAbsolutePath(), 0);
        } catch (Throwable ignored) {
            // 解析失败按不可用处理
        }
        var archiveVersion = archive == null || archive.versionName == null
            ? "" : archive.versionName;
        if (archive == null || (!archiveVersion.isEmpty()
            && compare(archiveVersion, currentVersion(context)) <= 0)) {
            prefs.edit().remove(K_APK_PATH).apply();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
        return file;
    }

    /**
     * 请求最新版本
     *
     * @return false 表示已有检查任务在进行
     */
    public static boolean check(Context context, CheckCallback callback) {
        if (!CHECKING.compareAndSet(false, true)) {
            return false;
        }
        var app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                var info = fetchLatest();
                saveLatest(app, info);
                var newer = compare(info.version, currentVersion(app)) > 0;
                LogHelp.i(TAG, "update check: latest=" + info.version
                    + " current=" + currentVersion(app) + " newer=" + newer);
                callback.onSuccess(info, newer);
            } catch (Throwable e) {
                LogHelp.w(TAG, "update check failed: " + e.getMessage());
                callback.onError(readable(e));
            } finally {
                CHECKING.set(false);
            }
        });
        return true;
    }

    /**
     * 下载最新安装包
     *
     * @return false 表示已有下载任务在进行
     */
    public static boolean download(Context context, UpdateInfo info, DownloadCallback callback) {
        if (info == null || !info.hasApk()) {
            callback.onError("该版本没有提供 APK 附件");
            return false;
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            return false;
        }
        var app = context.getApplicationContext();
        sProgress = -1;
        WORKER.execute(() -> {
            File part = null;
            try {
                var dir = apkDir(app);
                var target = new File(dir, apkFileName(info));
                // 之前已经完整下载过同一个包时直接复用
                if (target.isFile() && target.length() > 0
                    && (info.apkSize <= 0 || target.length() == info.apkSize)) {
                    try {
                        verify(app, target);
                    } catch (Throwable e) {
                        // 旧文件不合法时清掉，避免每次点下载都直接失败
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                        throw e;
                    }
                    saveApkPath(app, target);
                    callback.onSuccess(target);
                    return;
                }
                part = new File(dir, target.getName() + ".part");
                if (part.exists() && !part.delete()) {
                    throw new IOException("无法清理旧的下载文件");
                }
                downloadFile(info.apkUrl, part, info.apkSize, callback);
                verify(app, part);
                if (target.exists() && !target.delete()) {
                    throw new IOException("无法覆盖旧的安装包");
                }
                if (!part.renameTo(target)) {
                    copyFile(part, target);
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
                part = null;
                saveApkPath(app, target);
                deleteOtherApks(dir, target);
                sProgress = 100;
                LogHelp.i(TAG, "update downloaded: " + target.getName()
                    + " size=" + target.length());
                callback.onSuccess(target);
            } catch (Throwable e) {
                LogHelp.w(TAG, "update download failed: " + e.getMessage());
                sProgress = -1;
                callback.onError(readable(e));
            } finally {
                if (part != null) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
                DOWNLOADING.set(false);
            }
        });
        return true;
    }

    /**
     * 调用系统安装器安装已下载的 APK
     *
     * @return 是否成功拉起安装界面
     */
    public static boolean install(Activity activity, File apk) {
        if (apk == null || !apk.isFile() || apk.length() <= 0) {
            toast(activity, "安装包不存在，请重新下载");
            return false;
        }
        var manager = activity.getPackageManager();
        if (!manager.canRequestPackageInstalls()) {
            toast(activity, "请先允许本应用安装未知应用，然后返回重试");
            try {
                activity.startActivity(new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            } catch (Throwable e) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            return false;
        }
        try {
            var uri = UpdateFileProvider.uriFor(activity, apk);
            var intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
            return true;
        } catch (Throwable e) {
            LogHelp.w(TAG, "start installer failed: " + e.getMessage());
            toast(activity, "无法打开系统安装器：" + readable(e));
            return false;
        }
    }

    /**
     * 打开 GitHub 发布页
     */
    public static void openReleasesPage(Context context) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable e) {
            toast(context, "无法打开浏览器");
        }
    }

    /**
     * 安装包存放目录，优先外部私有目录，方便系统安装器读取
     */
    public static File apkDir(Context context) {
        File dir = null;
        try {
            var external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (external != null) {
                dir = new File(external, "updates");
            }
        } catch (Throwable ignored) {
            // 外部存储不可用时退回内部目录
        }
        if (dir == null) {
            dir = new File(context.getFilesDir(), "updates");
        }
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            LogHelp.w(TAG, "create update dir failed: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * 字节数转可读文本
     */
    public static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "未知大小";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f));
    }

    /**
     * 版本号比较，返回 -1 / 0 / 1
     *
     * 按点分段做数值比较，忽略 v 前缀与 -rc1 之类的后缀。
     */
    public static int compare(String left, String right) {
        var a = numericParts(left);
        var b = numericParts(right);
        var length = Math.max(a.size(), b.size());
        for (var i = 0; i < length; i++) {
            var x = i < a.size() ? a.get(i) : 0L;
            var y = i < b.size() ? b.get(i) : 0L;
            if (x != y) {
                return x > y ? 1 : -1;
            }
        }
        return 0;
    }

    private static List<Long> numericParts(String version) {
        var parts = new ArrayList<Long>();
        if (version == null) {
            return parts;
        }
        var text = version.trim();
        if (text.startsWith("v") || text.startsWith("V")) {
            text = text.substring(1);
        }
        var cut = text.indexOf('-');
        if (cut >= 0) {
            text = text.substring(0, cut);
        }
        cut = text.indexOf('+');
        if (cut >= 0) {
            text = text.substring(0, cut);
        }
        for (var part : text.split("\\.")) {
            var digits = part.replaceAll("[^0-9]", "");
            try {
                parts.add(digits.isEmpty() ? 0L : Long.parseLong(digits));
            } catch (Exception e) {
                parts.add(0L);
            }
        }
        return parts;
    }

    /**
     * 去掉 tag 的 v 前缀，得到纯版本号
     */
    private static String normalizeVersion(String tag) {
        var text = tag == null ? "" : tag.trim();
        if (text.startsWith("v") || text.startsWith("V")) {
            text = text.substring(1);
        }
        var cut = text.indexOf('-');
        if (cut >= 0) {
            text = text.substring(0, cut);
        }
        return text.trim();
    }

    /**
     * 请求 GitHub API 并解析最新 Release
     */
    private static UpdateInfo fetchLatest() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API_LATEST).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "XpWeChatCall-Updater");
            var code = connection.getResponseCode();
            if (code == 404) {
                throw new IOException("仓库还没有发布 Release，或最新版本只有草稿/预发布");
            }
            if (code == 403 || code == 429) {
                throw new IOException("GitHub API 触发限流，请稍后再试，或打开发布页手动下载");
            }
            if (code != 200) {
                throw new IOException("GitHub 返回 HTTP " + code);
            }
            return parseRelease(readString(connection.getInputStream()));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 解析 Release JSON
     */
    private static UpdateInfo parseRelease(String body) throws Exception {
        var json = new JSONObject(body);
        var tag = json.optString("tag_name", "").trim();
        var version = normalizeVersion(tag);
        if (version.isEmpty()) {
            throw new IOException("Release 缺少可用的版本号");
        }
        var title = json.optString("name", "").trim();
        if (title.isEmpty()) {
            title = tag;
        }
        var notes = json.optString("body", "").trim();
        if (notes.isEmpty()) {
            notes = "该版本没有填写更新说明。";
        }
        var htmlUrl = json.optString("html_url", "");
        var published = json.optString("published_at", "");
        var publishedAt = parseTime(published);

        String apkUrl = "";
        String apkName = "";
        long apkSize = 0L;
        var assets = json.optJSONArray("assets");
        if (assets != null) {
            // 优先挑选文件名以项目名开头的 APK
            for (var i = 0; i < assets.length(); i++) {
                var asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                var name = asset.optString("name", "");
                var url = asset.optString("browser_download_url", "");
                if (name.isEmpty() || url.isEmpty()
                    || !name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    continue;
                }
                var preferred = name.toLowerCase(Locale.ROOT).startsWith("xpwechatecall");
                if (apkUrl.isEmpty() || (preferred && !apkName.toLowerCase(Locale.ROOT)
                    .startsWith("xpwechatecall"))) {
                    apkUrl = url;
                    apkName = name;
                    apkSize = asset.optLong("size", 0L);
                }
            }
        }
        return new UpdateInfo(tag, version, title, notes, htmlUrl, apkUrl, apkName,
            apkSize, publishedAt);
    }

    private static long parseTime(String iso) {
        if (iso == null || iso.isEmpty()) {
            return 0L;
        }
        try {
            var format = new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            var time = format.parse(iso);
            return time == null ? 0L : time.getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 读取响应体，限制最大长度
     */
    private static String readString(InputStream input) throws IOException {
        try (var source = new BufferedInputStream(input);
             var out = new ByteArrayOutputStream()) {
            var buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_RESPONSE) {
                    throw new IOException("GitHub 响应过大");
                }
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * 下载到临时文件，边下边回调进度
     */
    private static void downloadFile(String url, File target, long expected,
                                     DownloadCallback callback) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("User-Agent", "XpWeChatCall-Updater");
            var code = connection.getResponseCode();
            if (code != 200) {
                throw new IOException("下载失败，HTTP " + code);
            }
            var total = expected > 0 ? expected : connection.getContentLengthLong();
            var buffer = new byte[16 * 1024];
            long downloaded = 0L;
            long lastReport = 0L;
            sProgress = total > 0 ? 0 : -1;
            try (var in = new BufferedInputStream(connection.getInputStream());
                 var out = new FileOutputStream(target)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    var now = System.currentTimeMillis();
                    if (now - lastReport >= 150) {
                        lastReport = now;
                        var percent = total > 0 ? (int) (downloaded * 100 / total) : -1;
                        sProgress = percent;
                        callback.onProgress(percent, downloaded, total);
                    }
                }
                out.flush();
                out.getFD().sync();
            }
            callback.onProgress(100, downloaded, total);
            if (total > 0 && downloaded != total) {
                throw new IOException("下载不完整：" + downloaded + "/" + total + " 字节");
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 校验安装包：包名、版本不能回退、签名必须与当前安装版本一致
     */
    private static void verify(Context context, File apk) throws IOException {
        var manager = context.getPackageManager();
        var flags = PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES;
        var archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null) {
            throw new IOException("无法解析下载的安装包，可能文件损坏");
        }
        if (!context.getPackageName().equals(archive.packageName)) {
            throw new IOException("安装包包名不匹配：" + archive.packageName);
        }
        PackageInfo installed;
        try {
            installed = manager.getPackageInfo(context.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("无法读取当前安装版本信息");
        }
        if (archive.getLongVersionCode() < installed.getLongVersionCode()) {
            throw new IOException("安装包版本低于当前已安装版本，无法覆盖安装");
        }
        var apkSignature = signatureDigest(archive);
        var localSignature = signatureDigest(installed);
        if (apkSignature != null && localSignature != null
            && !apkSignature.equals(localSignature)) {
            throw new IOException("安装包签名与当前应用不一致，无法覆盖安装。"
                + "请下载与本机同一签名的安装包，或先卸载旧版本");
        }
    }

    /**
     * 取签名证书的 SHA-256，多次签名时按顺序拼接后取摘要
     */
    private static String signatureDigest(PackageInfo info) {
        try {
            // 优先比较 APK 实际签名者，避免密钥轮换历史导致误判
            Signature[] signatures = info.signingInfo == null
                ? null : info.signingInfo.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) {
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) {
                return null;
            }
            var digest = MessageDigest.getInstance("SHA-256");
            for (var signature : signatures) {
                digest.update(signature.toByteArray());
            }
            var bytes = digest.digest();
            var builder = new StringBuilder(bytes.length * 2);
            for (var b : bytes) {
                builder.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return builder.toString();
        } catch (Throwable e) {
            LogHelp.w(TAG, "signature digest failed: " + e.getMessage());
            return null;
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (var in = new java.io.FileInputStream(source);
             var out = new FileOutputStream(target)) {
            var buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    /**
     * 清理其它旧安装包，只保留刚下载的这个
     */
    private static void deleteOtherApks(File dir, File keep) {
        var files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (var file : files) {
            if (!file.equals(keep) && file.getName().endsWith(".apk")) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    /**
     * 下载文件使用的安全文件名
     */
    private static String apkFileName(UpdateInfo info) {
        var name = info.apkName == null ? "" : info.apkName;
        if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            name = "XpWeChatCall-" + info.version + ".apk";
        }
        var safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 80 || safe.length() < 5) {
            safe = "XpWeChatCall-" + info.version + ".apk";
        }
        return safe;
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void saveLatest(Context context, UpdateInfo info) {
        prefs(context).edit()
            .putString(K_LATEST, info.toJson().toString())
            .putLong(K_CHECKED_AT, System.currentTimeMillis())
            .apply();
    }

    private static void saveApkPath(Context context, File file) {
        prefs(context).edit().putString(K_APK_PATH, file.getAbsolutePath()).apply();
    }

    /**
     * 把异常转成给用户看的文案
     */
    private static String readable(Throwable e) {
        if (e instanceof UnknownHostException) {
            return "无法连接 GitHub，请检查网络";
        }
        if (e instanceof SocketTimeoutException) {
            return "连接 GitHub 超时，请稍后重试";
        }
        var message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        return message;
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
