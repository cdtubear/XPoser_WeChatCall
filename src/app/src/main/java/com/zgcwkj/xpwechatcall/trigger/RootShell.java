package com.zgcwkj.xpwechatcall.trigger;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;

import com.zgcwkj.comm.LogHelp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * root 命令封装
 *
 * 只用于两件事：
 *   1. 后台拉起微信主界面（绕过 Android 10+ 后台启动 Activity 限制）
 *   2. 唤醒屏幕并解除滑动锁屏
 *
 * 所有拼接到命令行的参数都先经过白名单校验，避免命令注入。
 */
public final class RootShell {

    private static final String TAG = "XpWeChatCall";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI";
    /** NEW_TASK | CLEAR_TOP | SINGLE_TOP */
    private static final String FLAGS = "0x34000000";
    private static final Pattern SIZE_PATTERN = Pattern.compile("(\\d+)\\s*x\\s*(\\d+)");

    /** 0=未检测，1=有 root，2=无 root */
    private static volatile int sRootState;
    private static volatile long sRootCheckedAt;

    private RootShell() {
    }

    public static final class Result {
        public final boolean ok;
        public final String output;

        Result(boolean ok, String output) {
            this.ok = ok;
            this.output = output == null ? "" : output;
        }
    }

    /**
     * root 状态，仅使用缓存结果
     */
    public static String cachedRootStatus() {
        if (sRootState == 1) {
            return "granted";
        }
        if (sRootState == 2) {
            return "denied";
        }
        return "unknown";
    }

    /**
     * 检查 root，30 秒内复用结果
     */
    public static boolean hasRoot(Context context) {
        var now = System.currentTimeMillis();
        if (sRootState != 0 && now - sRootCheckedAt < 30_000L) {
            return sRootState == 1;
        }
        var result = exec(new String[]{"su", "-c", "id"});
        var granted = result.ok && result.output.contains("uid=0");
        sRootState = granted ? 1 : 2;
        sRootCheckedAt = now;
        LogHelp.i(TAG, "root check: " + (granted ? "granted" : "denied"));
        return granted;
    }

    /**
     * 强制重新检查 root
     */
    public static boolean checkRoot(Context context) {
        sRootState = 0;
        sRootCheckedAt = 0L;
        return hasRoot(context);
    }

    public static boolean isWeChatInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(WECHAT_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 当前是否处于锁屏状态
     */
    public static boolean isDeviceLocked(Context context) {
        var keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    /**
     * 唤醒屏幕并解除锁屏
     *
     * @return true=已解锁或本来就未锁屏；false=仍然锁屏（例如设置了密码）
     */
    public static boolean wakeAndUnlock(Context context) {
        if (!hasRoot(context)) {
            return false;
        }
        try {
            execRoot("input keyevent KEYCODE_WAKEUP");
            sleep(400);
            execRoot("wm dismiss-keyguard");
            sleep(600);

            var keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguard == null || !keyguard.isKeyguardLocked()) {
                return true;
            }

            // 滑动锁：从屏幕下方往上滑，最多尝试两次
            var size = screenSize();
            for (var i = 0; i < 2 && keyguard.isKeyguardLocked(); i++) {
                var x = size[0] / 2;
                execRoot("input swipe " + x + " " + (int) (size[1] * 0.85f)
                    + " " + x + " " + (int) (size[1] * 0.25f) + " 220");
                sleep(500);
            }
            // 部分 ROM 需要 MENU 键才能收起锁屏
            execRoot("input keyevent KEYCODE_MENU");
            sleep(500);
            return !keyguard.isKeyguardLocked();
        } catch (Throwable e) {
            LogHelp.e(TAG, "wake and unlock failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 拉起微信主界面并透传通话参数
     */
    public static Result launchWeChat(String wxid, String callType, String requestId) {
        if (!isSafeWxid(wxid) || !isSafeRequestId(requestId)) {
            return new Result(false, "invalid argument");
        }
        var type = "voice".equals(callType) ? "voice" : "video";
        var command = "am start -W -f " + FLAGS
            + " -n " + WECHAT_PACKAGE + "/" + LAUNCHER_UI
            + " --es xp_wxid '" + wxid + "'"
            + " --es xp_call_type '" + type + "'"
            + " --es xp_request_id '" + requestId + "'";
        var result = execRoot(command);
        var output = result.output.trim();
        var launched = result.ok && (output.contains("Status: ok")
            || output.contains("Activity: " + WECHAT_PACKAGE))
            && !output.contains("Error:");
        if (!launched) {
            LogHelp.w(TAG, "launch wechat failed: " + output);
        }
        return new Result(launched, output);
    }

    private static boolean isSafeWxid(String wxid) {
        return wxid != null && wxid.matches("[A-Za-z0-9_.@-]{1,128}");
    }

    private static boolean isSafeRequestId(String id) {
        return id != null && id.matches("[A-Za-z0-9_.:-]{1,64}");
    }

    /**
     * 读取屏幕分辨率，优先使用 override size
     */
    private static int[] screenSize() {
        var result = execRoot("wm size");
        var text = result.output;
        int width = 1080;
        int height = 2400;
        var matcher = SIZE_PATTERN.matcher(text);
        while (matcher.find()) {
            try {
                width = Integer.parseInt(matcher.group(1));
                height = Integer.parseInt(matcher.group(2));
            } catch (Exception ignored) {
                // 保留默认值
            }
        }
        return new int[]{width, height};
    }

    private static Result execRoot(String command) {
        return exec(new String[]{"su", "-c", command});
    }

    /**
     * 执行命令并等待结果，超时后强制结束进程
     */
    private static Result exec(String[] command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy();
                return new Result(false, "command timeout");
            }
            var output = readAll(process.getInputStream());
            return new Result(process.exitValue() == 0, output);
        } catch (Throwable e) {
            LogHelp.e(TAG, "exec failed: " + e.getMessage(), e);
            return new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    private static String readAll(InputStream input) throws Exception {
        try (var out = new ByteArrayOutputStream()) {
            var buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
