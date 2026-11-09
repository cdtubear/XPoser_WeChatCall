package com.zgcwkj.comm;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 外部触发接口的共享契约
 *
 * 模块 App 进程与微信进程（Hook）都需要用到方法名、状态和配置键，
 * 因此放在 comm 包中，避免两边各写一份常量。
 */
public final class TriggerContract {

    /** ContentProvider 授权标识，与 AndroidManifest 保持一致 */
    public static final String AUTHORITY = "com.cdtubear.xpwechatcall.trigger";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    /** 对外方法名 */
    public static final String METHOD_RESOLVE = "resolve_friend";
    public static final String METHOD_FRIENDS = "list_friends";
    public static final String METHOD_START = "start_call";
    public static final String METHOD_QUERY = "query_result";
    public static final String METHOD_HEALTH = "health";

    /** 微信进程内部回传结果的方法名 */
    public static final String METHOD_REPORT = "report_result";

    /** Provider 参数：调用方 Token 与 JSON 内容 */
    public static final String EXTRA_AUTH = "auth";
    public static final String EXTRA_JSON = "json";
    public static final String RESULT_KEY = "result";

    /** 透传给微信进程的请求标识 */
    public static final String EXTRA_REQUEST_ID = "xp_request_id";

    /** 通话类型 */
    public static final String CALL_VIDEO = "video";
    public static final String CALL_VOICE = "voice";

    /** 请求状态 */
    public static final String S_ACCEPTED = "ACCEPTED";
    public static final String S_LAUNCHED = "LAUNCHED";
    public static final String S_CHAT_OPENED = "CHAT_OPENED";
    public static final String S_PANEL_OPENED = "PANEL_OPENED";
    public static final String S_CALL_MENU_OPENED = "CALL_MENU_OPENED";
    public static final String S_SUCCESS = "SUCCESS";
    public static final String S_FAILED = "FAILED";

    /** 错误码 */
    public static final String E_UNAUTHORIZED = "UNAUTHORIZED";
    public static final String E_DISABLED = "DISABLED";
    public static final String E_RATE_LIMITED = "RATE_LIMITED";
    public static final String E_BAD_REQUEST = "BAD_REQUEST";
    public static final String E_BUSY = "BUSY";
    public static final String E_NOT_FOUND = "NOT_FOUND";
    public static final String E_EXPIRED = "RESULT_EXPIRED";
    public static final String E_FRIEND_NOT_FOUND = "FRIEND_NOT_FOUND";
    public static final String E_FRIEND_AMBIGUOUS = "FRIEND_AMBIGUOUS";
    public static final String E_NOT_SYNCED = "NOT_SYNCED";
    public static final String E_WECHAT_NOT_INSTALLED = "WECHAT_NOT_INSTALLED";
    public static final String E_MODULE_INACTIVE = "MODULE_INACTIVE";
    public static final String E_ROOT_DENIED = "ROOT_DENIED";
    public static final String E_LAUNCH_BLOCKED = "LAUNCH_BLOCKED";
    public static final String E_DEVICE_LOCKED = "DEVICE_LOCKED";
    public static final String E_CHAT_OPEN_FAILED = "CHAT_OPEN_FAILED";
    public static final String E_CALL_BUTTON_NOT_FOUND = "CALL_BUTTON_NOT_FOUND";
    public static final String E_TIMEOUT = "TIMEOUT";
    public static final String E_INTERNAL = "INTERNAL";
    public static final String E_BT_NOT_BONDED = "BT_NOT_BONDED";
    public static final String E_BT_UNAVAILABLE = "BT_UNAVAILABLE";

    /** 配置存储 */
    private static final String PREFS = "trigger_config";
    public static final String K_ENABLED = "enabled";
    public static final String K_BIND = "bind";
    public static final String K_PORT = "port";
    public static final String K_TOKEN = "token";
    public static final String K_AUTO_UNLOCK = "auto_unlock";
    public static final String K_RATE_LIMIT = "rate_limit";
    public static final String K_TIMEOUT = "timeout_sec";
    public static final String K_CALLBACK_HOSTS = "callback_hosts";
    public static final String K_ALLOWED_PACKAGES = "allowed_packages";
    public static final String K_LAST_HOOK_REPORT = "last_hook_report";

    /** 蓝牙触发配置 */
    public static final String K_BT_ENABLED = "bt_enabled";
    public static final String K_BT_REQUIRE_BOND = "bt_require_bond";

    /** 默认值：默认关闭，开启后监听所有网卡，必须携带 Token */
    public static final boolean DEF_ENABLED = false;
    public static final String DEF_BIND = "0.0.0.0";
    public static final int DEF_PORT = 8790;
    public static final boolean DEF_AUTO_UNLOCK = true;
    public static final int DEF_RATE_LIMIT = 10;
    public static final int DEF_TIMEOUT = 30;
    public static final boolean DEF_BT_ENABLED = false;
    public static final boolean DEF_BT_REQUIRE_BOND = true;

    private TriggerContract() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(K_ENABLED, DEF_ENABLED);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(K_ENABLED, enabled).commit();
    }

    public static boolean isBtEnabled(Context context) {
        return prefs(context).getBoolean(K_BT_ENABLED, DEF_BT_ENABLED);
    }

    public static void setBtEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(K_BT_ENABLED, enabled).commit();
    }

    public static boolean btRequireBond(Context context) {
        return prefs(context).getBoolean(K_BT_REQUIRE_BOND, DEF_BT_REQUIRE_BOND);
    }

    public static void setBtRequireBond(Context context, boolean require) {
        prefs(context).edit().putBoolean(K_BT_REQUIRE_BOND, require).commit();
    }

    public static String bind(Context context) {
        var value = prefs(context).getString(K_BIND, DEF_BIND);
        return value == null || value.trim().isEmpty() ? DEF_BIND : value.trim();
    }

    public static int port(Context context) {
        return prefs(context).getInt(K_PORT, DEF_PORT);
    }

    public static boolean autoUnlock(Context context) {
        return prefs(context).getBoolean(K_AUTO_UNLOCK, DEF_AUTO_UNLOCK);
    }

    public static int rateLimit(Context context) {
        return prefs(context).getInt(K_RATE_LIMIT, DEF_RATE_LIMIT);
    }

    public static int timeoutSec(Context context) {
        var value = prefs(context).getInt(K_TIMEOUT, DEF_TIMEOUT);
        return value <= 0 ? DEF_TIMEOUT : value;
    }

    public static String callbackHosts(Context context) {
        var value = prefs(context).getString(K_CALLBACK_HOSTS, "");
        return value == null ? "" : value.trim();
    }

    public static String allowedPackages(Context context) {
        var value = prefs(context).getString(K_ALLOWED_PACKAGES, "");
        return value == null ? "" : value.trim();
    }

    public static long lastHookReport(Context context) {
        return prefs(context).getLong(K_LAST_HOOK_REPORT, 0L);
    }

    public static void setLastHookReport(Context context, long time) {
        prefs(context).edit().putLong(K_LAST_HOOK_REPORT, time).apply();
    }

    /**
     * 读取 Token，首次访问时自动生成并持久化
     */
    public static synchronized String token(Context context) {
        var prefs = prefs(context);
        var token = prefs.getString(K_TOKEN, "");
        if (token != null && !token.isEmpty()) {
            return token;
        }
        token = newToken();
        prefs.edit().putString(K_TOKEN, token).commit();
        return token;
    }

    public static String newToken() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes,
            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /**
     * 状态优先级，用于防止旧状态覆盖新状态
     */
    public static int stateRank(String state) {
        if (state == null) {
            return 0;
        }
        switch (state) {
            case S_ACCEPTED:
                return 1;
            case S_LAUNCHED:
                return 2;
            case S_CHAT_OPENED:
                return 3;
            case S_PANEL_OPENED:
                return 4;
            case S_CALL_MENU_OPENED:
                return 5;
            case S_SUCCESS:
                return 6;
            case S_FAILED:
                return 99;
            default:
                return 0;
        }
    }

    public static boolean isTerminal(String state) {
        return S_SUCCESS.equals(state) || S_FAILED.equals(state);
    }

    /**
     * 判断状态字符串是否合法，避免 Hook 回传任意内容
     */
    public static boolean isValidStep(String state) {
        var rank = stateRank(state);
        return rank > 0 && rank < 99;
    }

    /**
     * 规范化请求标识，非法字符直接拒绝
     */
    public static boolean isValidRequestId(String id) {
        return id != null && id.matches("[A-Za-z0-9_.:-]{1,64}");
    }

    /**
     * 规范化 wxid，限制可用字符，防止拼接到 root 命令时被注入
     */
    public static boolean isValidWxid(String wxid) {
        return wxid != null && wxid.matches("[A-Za-z0-9_.@-]{1,128}");
    }

    /**
     * 提取 JSON 中的字符串并转小写
     */
    public static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
