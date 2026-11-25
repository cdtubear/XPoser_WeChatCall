package com.cdtubear.xpwechatcall.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 更新安装包的文件通道
 *
 * 系统安装器需要通过 content:// 读取 APK，工程里没有依赖 androidx，
 * 因此实现一个最小的只读 Provider：只暴露下载目录下的 .apk 文件，
 * 其它路径一律拒绝，避免变成任意文件读取入口。
 */
public class UpdateFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.cdtubear.xpwechatcall.update";

    private static final int MATCH_APK = 1;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        // 只接受单段路径，具体文件名与后缀在 resolve() 里校验
        MATCHER.addURI(AUTHORITY, "*", MATCH_APK);
    }

    /**
     * 为下载好的安装包生成可授权读取的 URI
     */
    public static Uri uriFor(android.content.Context context, File file) {
        return new Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(file.getName())
            .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("only read is allowed");
        }
        var file = resolve(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        var columns = projection != null ? projection
            : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        var cursor = new MatrixCursor(columns, 1);
        var values = new Object[columns.length];
        for (var i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                values[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                values[i] = file.length();
            }
        }
        cursor.addRow(values);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    /**
     * 把 URI 解析成下载目录内的文件，越界或非 APK 一律拒绝
     */
    private File resolve(Uri uri) throws FileNotFoundException {
        if (uri == null || MATCHER.match(uri) != MATCH_APK) {
            throw new FileNotFoundException("unsupported uri: " + uri);
        }
        var name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
            || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
            throw new FileNotFoundException("invalid file name");
        }
        var context = getContext();
        if (context == null) {
            throw new FileNotFoundException("no context");
        }
        var dir = UpdateManager.apkDir(context);
        var file = new File(dir, name);
        try {
            var root = dir.getCanonicalPath();
            var path = file.getCanonicalPath();
            if (!path.startsWith(root + File.separator)) {
                throw new FileNotFoundException("path out of update dir");
            }
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("invalid path");
        }
        if (!file.isFile() || file.length() <= 0) {
            throw new FileNotFoundException("apk not found");
        }
        return file;
    }
}
