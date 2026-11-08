package com.zgcwkj.xpwechatcall.trigger;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.TriggerContract;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 外部触发 Provider
 *
 * 与 HTTP 服务共用 TriggerApi，主要给 Tasker / adb / 其它 App 使用。
 * report_result 只接受微信进程调用；其它方法必须携带 Token。
 */
public class TriggerProvider extends ContentProvider {

    private static final String TAG = "XpWeChatCall";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        var context = getContext();
        if (context == null) {
            return bundle(error(TriggerContract.E_INTERNAL, "no context"));
        }
        try {
            if (TriggerContract.METHOD_REPORT.equals(method)) {
                if (!isFromWeChat()) {
                    LogHelp.w(TAG, "reject report from uid=" + Binder.getCallingUid());
                    return bundle(error(TriggerContract.E_UNAUTHORIZED, "only WeChat can report"));
                }
                var request = parse(arg);
                if (request == null) {
                    return bundle(error(TriggerContract.E_BAD_REQUEST, "invalid json"));
                }
                return bundle(TriggerApi.report(context, request));
            }

            if (!isAuthorized(extras)) {
                LogHelp.w(TAG, "reject call from uid=" + Binder.getCallingUid());
                return bundle(error(TriggerContract.E_UNAUTHORIZED, "invalid token"));
            }
            if (!TriggerContract.isEnabled(context)) {
                return bundle(error(TriggerContract.E_DISABLED, "external trigger is disabled"));
            }

            switch (method) {
                case TriggerContract.METHOD_RESOLVE:
                    return bundle(TriggerApi.resolve(context, parse(arg)));
                case TriggerContract.METHOD_FRIENDS:
                    return bundle(TriggerApi.listFriends(context, parse(arg)));
                case TriggerContract.METHOD_START:
                    return bundle(TriggerApi.startCall(context, parse(arg)));
                case TriggerContract.METHOD_QUERY:
                    return bundle(TriggerApi.query(context, arg));
                case TriggerContract.METHOD_HEALTH:
                    return bundle(TriggerApi.health(context));
                default:
                    return bundle(error(TriggerContract.E_NOT_FOUND, "unknown method"));
            }
        } catch (Throwable e) {
            LogHelp.e(TAG, "provider call failed: " + e.getMessage(), e);
            return bundle(error(TriggerContract.E_INTERNAL, e.toString()));
        }
    }

    private boolean isAuthorized(Bundle extras) {
        var provided = extras == null ? null : extras.getString(TriggerContract.EXTRA_AUTH);
        var expected = TriggerContract.token(getContext());
        if (!safeEquals(expected, provided)) {
            return false;
        }
        // 可选包名白名单，配置为空时只校验 Token
        var allowed = TriggerContract.allowedPackages(getContext());
        if (allowed.isEmpty()) {
            return true;
        }
        try {
            var packages = getContext().getPackageManager()
                .getPackagesForUid(Binder.getCallingUid());
            if (packages == null) {
                return false;
            }
            for (var pkg : packages) {
                for (var item : allowed.split(",")) {
                    if (pkg.equals(item.trim())) {
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            LogHelp.e(TAG, "check caller package failed: " + e.getMessage(), e);
        }
        return false;
    }

    private boolean isFromWeChat() {
        try {
            var packages = getContext().getPackageManager()
                .getPackagesForUid(Binder.getCallingUid());
            if (packages != null) {
                for (var pkg : packages) {
                    if (WECHAT_PACKAGE.equals(pkg)) {
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            LogHelp.e(TAG, "check wechat uid failed: " + e.getMessage(), e);
        }
        return false;
    }

    private static JSONObject parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bundle bundle(JSONObject json) {
        var bundle = new Bundle();
        bundle.putString(TriggerContract.RESULT_KEY, json == null ? "{}" : json.toString());
        return bundle;
    }

    private static JSONObject error(String code, String message) {
        var json = new JSONObject();
        try {
            json.put("ok", false);
            json.put("error", code);
            json.put("message", message);
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }

    private static boolean safeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        var a = expected.getBytes(StandardCharsets.UTF_8);
        var b = provided.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        var result = 0;
        for (var i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
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
}
