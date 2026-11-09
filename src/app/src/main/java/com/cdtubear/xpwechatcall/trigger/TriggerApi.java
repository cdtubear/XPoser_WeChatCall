package com.cdtubear.xpwechatcall.trigger;

import android.content.Context;
import android.net.Uri;

import com.zgcwkj.comm.FriendIndex;
import com.zgcwkj.comm.FriendSync;
import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.TriggerContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 外部触发核心逻辑
 *
 * HTTP 服务与 ContentProvider 共用这一层，保证两条入口的行为完全一致。
 */
public final class TriggerApi {

    private static final String TAG = "XpWeChatCall";

    public interface Listener {
        void onTerminal(Context context, JSONObject record);
    }

    private static volatile Listener sListener;

    private TriggerApi() {
    }

    public static void setListener(Listener listener) {
        sListener = listener;
    }

    /**
     * 昵称 -> wxid
     */
    public static JSONObject resolve(Context context, JSONObject request) {
        var name = request == null ? "" : request.optString("name", "").trim();
        if (name.isEmpty()) {
            return error(TriggerContract.E_BAD_REQUEST, "name is required");
        }
        var fuzzy = request.optBoolean("fuzzy", true);
        var limit = request.optInt("limit", 20);
        var data = FriendIndex.resolve(context, name, fuzzy, limit);
        if (data == null) {
            return error(TriggerContract.E_NOT_SYNCED,
                "friends not synced, import friends in the app first");
        }
        return with(ok(), "data", data);
    }

    /**
     * 获取好友列表
     *
     * 参数：offset（默认 0）、limit（<= 0 表示全部）、keyword（可选）
     */
    public static JSONObject listFriends(Context context, JSONObject request) {
        var offset = request == null ? 0 : request.optInt("offset", 0);
        var limit = request == null ? 0 : request.optInt("limit", 0);
        var keyword = request == null ? "" : request.optString("keyword", "");
        return listFriends(context, offset, limit, keyword);
    }

    /**
     * 获取好友列表
     */
    public static JSONObject listFriends(Context context, int offset, int limit, String keyword) {
        var data = FriendIndex.list(context, offset, limit, keyword);
        if (data == null) {
            return error(TriggerContract.E_NOT_SYNCED,
                "friends not synced, import friends in the app first");
        }
        return with(ok(), "data", data);
    }

    /**
     * 发起通话
     *
     * 参数：wxid 或 name 二选一，type=video/voice，request_id 可选，
     * callback_url 可选，timeout_sec 可选，pick_first 可选。
     */
    public static JSONObject startCall(Context context, JSONObject request) {
        return startCall(context, request, false);
    }

    /**
     * 发起通话
     *
     * @param fromBluetooth 蓝牙入口允许在 HTTP 关闭但蓝牙开启时发起
     */
    public static JSONObject startCall(Context context, JSONObject request, boolean fromBluetooth) {
        if (request == null) {
            return error(TriggerContract.E_BAD_REQUEST, "json body is required");
        }
        if (fromBluetooth) {
            if (!TriggerContract.isBtEnabled(context)) {
                return error(TriggerContract.E_DISABLED, "bluetooth trigger is disabled");
            }
        } else if (!TriggerContract.isEnabled(context)) {
            return error(TriggerContract.E_DISABLED, "external trigger is disabled");
        }
        if (!RootShell.isWeChatInstalled(context)) {
            return error(TriggerContract.E_WECHAT_NOT_INSTALLED, "WeChat is not installed");
        }

        var type = normalizeType(request.optString("type", TriggerContract.CALL_VIDEO));
        if (type == null) {
            return error(TriggerContract.E_BAD_REQUEST, "type must be video or voice");
        }

        var id = request.optString("request_id", "").trim();
        if (id.isEmpty()) {
            id = newRequestId();
        } else if (!TriggerContract.isValidRequestId(id)) {
            return error(TriggerContract.E_BAD_REQUEST,
                "request_id allows A-Z a-z 0-9 _ . : - and max 64 chars");
        }

        var existing = TriggerStore.get(context, id);
        if (existing != null) {
            var response = ok();
            with(response, "request_id", id);
            with(response, "state", existing.optString("state", ""));
            with(response, "duplicate", true);
            with(response, "wxid", existing.optString("wxid", ""));
            with(response, "type", existing.optString("type", type));
            return response;
        }

        var busy = TriggerStore.busyRequestId(context);
        if (busy != null) {
            return with(error(TriggerContract.E_BUSY, "another call is in progress"),
                "current_request_id", busy);
        }

        var wxid = request.optString("wxid", "").trim();
        var name = request.optString("name", "").trim();
        JSONArray candidates = null;
        if (wxid.isEmpty()) {
            if (name.isEmpty()) {
                return error(TriggerContract.E_BAD_REQUEST, "wxid or name is required");
            }
            var data = FriendIndex.resolve(context, name, true, 20);
            if (data == null) {
                return error(TriggerContract.E_NOT_SYNCED,
                    "friends not synced, import friends in the app first");
            }
            var list = data.optJSONArray("candidates");
            var count = data.optInt("count", 0);
            if (count <= 0 || list == null || list.length() == 0) {
                return error(TriggerContract.E_FRIEND_NOT_FOUND, "friend not found: " + name);
            }
            // 只有最高匹配分存在多个好友时才算重名；唯一精确匹配可以自动选中
            var topScore = list.optJSONObject(0).optInt("score", 0);
            var topCount = 0;
            for (var i = 0; i < list.length(); i++) {
                if (list.optJSONObject(i).optInt("score", 0) == topScore) {
                    topCount++;
                }
            }
            if (topCount > 1 && !request.optBoolean("pick_first", false)) {
                var body = new JSONObject();
                with(body, "candidates", list);
                return with(error(TriggerContract.E_FRIEND_AMBIGUOUS,
                    "multiple friends matched, specify wxid"), "data", body);
            }
            wxid = list.optJSONObject(0).optString("wxid", "");
            candidates = list;
        }
        if (!TriggerContract.isValidWxid(wxid)) {
            return error(TriggerContract.E_BAD_REQUEST, "invalid wxid");
        }

        var callback = request.optString("callback_url", "").trim();
        if (!callback.isEmpty() && !callback.startsWith("http://") && !callback.startsWith("https://")) {
            return error(TriggerContract.E_BAD_REQUEST, "callback_url must be http(s)");
        }
        if (callback.length() > 512) {
            return error(TriggerContract.E_BAD_REQUEST, "callback_url is too long");
        }
        if (!callback.isEmpty() && !callbackAllowed(context, callback)) {
            return error(TriggerContract.E_BAD_REQUEST,
                "callback host is not in the whitelist configured in the app");
        }

        var timeout = request.optInt("timeout_sec", TriggerContract.timeoutSec(context));
        timeout = Math.max(5, Math.min(timeout, 120));

        TriggerStore.create(context, id, wxid, type, timeout, callback,
            fromBluetooth ? BtProtocol.SOURCE_BT : BtProtocol.SOURCE_HTTP);
        LogHelp.i(TAG, "trigger accepted id=" + id + " wxid=" + mask(wxid) + " type=" + type);

        final var requestId = id;
        final var callType = type;
        final var targetWxid = wxid;
        new Thread(() -> launch(context, requestId, targetWxid, callType), "xp-trigger-launch").start();

        var response = ok();
        with(response, "request_id", id);
        with(response, "state", TriggerContract.S_ACCEPTED);
        with(response, "wxid", wxid);
        with(response, "type", type);
        if (candidates != null) {
            with(response, "matched", candidates);
        }
        return response;
    }

    /**
     * 查询结果
     */
    public static JSONObject query(Context context, String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return error(TriggerContract.E_BAD_REQUEST, "request_id is required");
        }
        var id = requestId.trim();
        var timedOut = TriggerStore.checkTimeout(context, id);
        if (timedOut != null) {
            onTerminal(context, timedOut);
        }
        var record = TriggerStore.get(context, id);
        if (record == null) {
            return error(TriggerContract.E_NOT_FOUND, "request not found or expired");
        }
        return with(ok(), "data", record);
    }

    /**
     * 健康检查
     */
    public static JSONObject health(Context context) {
        var response = ok();
        try {
            response.put("module_version", "1.1.2");
            response.put("enabled", TriggerContract.isEnabled(context));
            response.put("service_running", TriggerService.isRunning());
            response.put("http_enabled", TriggerContract.isEnabled(context));
            response.put("http_running", TriggerService.isHttpRunning());
            response.put("bind", TriggerContract.bind(context));
            response.put("port", TriggerContract.port(context));
            response.put("token_set", !TriggerContract.token(context).isEmpty());
            response.put("bt_enabled", TriggerContract.isBtEnabled(context));
            response.put("bt_running", TriggerService.isBluetoothRunning());
            response.put("bt_advertising", TriggerService.isBluetoothAdvertising());
            response.put("bt_connected", TriggerService.bluetoothConnectedCount());
            response.put("bt_devices", TriggerService.bluetoothDevices());
            response.put("bt_last_command", TriggerService.bluetoothLastCommand());
            response.put("bt_last_result", TriggerService.bluetoothLastResult());
            response.put("bt_service_uuid", BtProtocol.SERVICE_UUID.toString());
            response.put("bt_rx_uuid", BtProtocol.RX_UUID.toString());
            response.put("bt_tx_uuid", BtProtocol.TX_UUID.toString());
            response.put("root", RootShell.cachedRootStatus());
            response.put("wechat_installed", RootShell.isWeChatInstalled(context));
            response.put("friends_count", FriendIndex.count(context));
            response.put("friends_synced_at", FriendSync.lastSyncTime(context));
            response.put("last_hook_report", TriggerContract.lastHookReport(context));
            response.put("auto_unlock", TriggerContract.autoUnlock(context));
            response.put("timeout_sec", TriggerContract.timeoutSec(context));
            var busy = TriggerStore.busyRequestId(context);
            response.put("busy", busy != null);
            response.put("current_request_id", busy == null ? "" : busy);
        } catch (Exception e) {
            return error(TriggerContract.E_INTERNAL, e.toString());
        }
        return response;
    }

    /**
     * 微信进程回传状态
     */
    public static JSONObject report(Context context, JSONObject request) {
        if (request == null) {
            return error(TriggerContract.E_BAD_REQUEST, "json body is required");
        }
        var id = request.optString("request_id", "").trim();
        var state = request.optString("state", "").trim();
        if (!TriggerContract.isValidRequestId(id)) {
            return error(TriggerContract.E_BAD_REQUEST, "invalid request_id");
        }
        // 正常步骤与 FAILED 都允许回传，方便微信进程报告点击失败
        if (!TriggerContract.isValidStep(state) && !TriggerContract.S_FAILED.equals(state)) {
            return error(TriggerContract.E_BAD_REQUEST, "invalid state");
        }
        var error = request.optString("error", "").trim();
        var detail = request.optString("detail", "").trim();
        var updated = TriggerStore.update(context, id, state, error, detail);
        if (updated == null) {
            return error(TriggerContract.E_NOT_FOUND, "request not found");
        }
        TriggerContract.setLastHookReport(context, System.currentTimeMillis());
        if (TriggerContract.isTerminal(updated.optString("state", ""))) {
            onTerminal(context, updated);
        }
        return with(ok(), "data", updated);
    }

    private static void launch(Context context, String requestId, String wxid, String type) {
        try {
            if (!RootShell.hasRoot(context)) {
                fail(context, requestId, TriggerContract.E_ROOT_DENIED,
                    "root is not granted");
                return;
            }
            if (TriggerContract.autoUnlock(context)) {
                if (!RootShell.wakeAndUnlock(context)) {
                    fail(context, requestId, TriggerContract.E_DEVICE_LOCKED,
                        "device is still locked after wake/unlock attempt");
                    return;
                }
            } else if (RootShell.isDeviceLocked(context)) {
                fail(context, requestId, TriggerContract.E_DEVICE_LOCKED,
                    "device is locked and auto unlock is disabled");
                return;
            }
            var result = RootShell.launchWeChat(wxid, type, requestId);
            if (!result.ok) {
                fail(context, requestId, TriggerContract.E_LAUNCH_BLOCKED, shorten(result.output));
                return;
            }
            TriggerStore.update(context, requestId, TriggerContract.S_LAUNCHED, null, null);
            LogHelp.i(TAG, "wechat launched id=" + requestId);
        } catch (Throwable e) {
            LogHelp.e(TAG, "launch failed: " + e.getMessage(), e);
            fail(context, requestId, TriggerContract.E_INTERNAL, e.toString());
        }
    }

    private static void fail(Context context, String requestId, String error, String detail) {
        var record = TriggerStore.update(context, requestId, TriggerContract.S_FAILED, error, detail);
        LogHelp.w(TAG, "trigger failed id=" + requestId + " error=" + error + " detail=" + detail);
        if (record != null) {
            onTerminal(context, record);
        }
    }

    /**
     * 由 HTTP 服务的超时扫描调用
     */
    public static void notifyTerminal(Context context, JSONObject record) {
        onTerminal(context, record);
    }

    /**
     * 终态回调：先通知本地监听（蓝牙），再领取发送权异步发送 HTTP 回调
     */
    private static void onTerminal(Context context, JSONObject record) {
        // 本地监听（蓝牙终态）始终通知，与是否配置 HTTP 回调无关
        var listener = sListener;
        if (listener != null) {
            listener.onTerminal(context, record);
        }
        var id = record.optString("request_id", "");
        var callback = record.optString("callback", "");
        if (callback.isEmpty()) {
            return;
        }
        if (!callbackAllowed(context, callback)) {
            LogHelp.w(TAG, "callback host not allowed: " + callback);
            return;
        }
        var claimed = TriggerStore.claimCallback(context, id);
        if (claimed == null) {
            return;
        }
        // 回调是网络请求，必须放到后台线程
        new Thread(() -> dispatchCallback(context, claimed), "xp-callback").start();
    }

    /**
     * 由 HTTP 服务在后台线程调用
     */
    public static void dispatchCallback(Context context, JSONObject record) {
        var url = record.optString("callback", "");
        if (url.isEmpty()) {
            return;
        }
        var payload = record.toString();
        var signature = hmacHex(TriggerContract.token(context), payload);
        for (var attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("X-Xp-Request-Id",
                    record.optString("request_id", ""));
                connection.setRequestProperty("X-Xp-Signature", "sha256=" + signature);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                var code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    LogHelp.i(TAG, "callback delivered id="
                        + record.optString("request_id", "") + " code=" + code);
                    return;
                }
                LogHelp.w(TAG, "callback failed code=" + code + " attempt=" + attempt);
            } catch (Throwable e) {
                LogHelp.w(TAG, "callback error attempt=" + attempt + ": " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            try {
                Thread.sleep(1000L * (attempt + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean callbackAllowed(Context context, String url) {
        var hosts = TriggerContract.callbackHosts(context);
        if (hosts.isEmpty()) {
            return false;
        }
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (var item : hosts.split(",")) {
            if (item.trim().equalsIgnoreCase(host)) {
                return true;
            }
        }
        return false;
    }

    private static String hmacHex(String secret, String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var bytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(bytes.length * 2);
            for (var b : bytes) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalizeType(String type) {
        var value = TriggerContract.lower(type);
        switch (value) {
            case "":
            case "video":
            case "视频":
            case "视频通话":
                return TriggerContract.CALL_VIDEO;
            case "voice":
            case "语音":
            case "语音通话":
                return TriggerContract.CALL_VOICE;
            default:
                return null;
        }
    }

    private static String newRequestId() {
        var random = new SecureRandom();
        return "xp_" + System.currentTimeMillis() + "_"
            + Integer.toHexString(random.nextInt() & 0xFFFFFF);
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        var value = text.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    private static String mask(String wxid) {
        if (wxid == null || wxid.length() <= 6) {
            return "***";
        }
        return wxid.substring(0, 3) + "***" + wxid.substring(wxid.length() - 3);
    }

    private static JSONObject ok() {
        var json = new JSONObject();
        try {
            json.put("ok", true);
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }

    /**
     * 安全写入字段，避免链式 put 的受检异常
     */
    private static JSONObject with(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }

    private static JSONObject error(String code, String message) {
        var json = new JSONObject();
        try {
            json.put("ok", false);
            json.put("error", code);
            json.put("message", message == null ? "" : message);
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }
}
