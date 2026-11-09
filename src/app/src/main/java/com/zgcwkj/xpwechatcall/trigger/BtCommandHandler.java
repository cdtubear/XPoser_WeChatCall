package com.zgcwkj.xpwechatcall.trigger;

import android.content.Context;

import com.zgcwkj.comm.TriggerContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 蓝牙指令分发
 *
 * 与 HTTP / ContentProvider 共用 TriggerApi，保证三条入口行为一致。
 * Token 与配对校验由 BluetoothTriggerManager 在调用这里之前完成。
 */
public final class BtCommandHandler {

    private BtCommandHandler() {
    }

    public static JSONObject handle(Context context, JSONObject request) {
        if (request == null) {
            return BtProtocol.error(TriggerContract.E_BAD_REQUEST, "json body is required");
        }
        var action = request.optString("action", BtProtocol.ACTION_CALL)
            .trim().toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case BtProtocol.ACTION_CALL:
                    return TriggerApi.startCall(context, request, true);
                case BtProtocol.ACTION_QUERY:
                    return TriggerApi.query(context, request.optString("request_id", ""));
                case BtProtocol.ACTION_HEALTH:
                    return TriggerApi.health(context);
                case BtProtocol.ACTION_FRIENDS:
                    return TriggerApi.listFriends(context, request);
                case BtProtocol.ACTION_RESOLVE:
                    return TriggerApi.resolve(context, request);
                case BtProtocol.ACTION_PING:
                    return BtProtocol.ok().put("pong", true)
                        .put("time", System.currentTimeMillis());
                case BtProtocol.ACTION_HELP:
                    return help();
                default:
                    return BtProtocol.error(TriggerContract.E_BAD_REQUEST,
                        "unknown action: " + action);
            }
        } catch (Throwable e) {
            return BtProtocol.error(TriggerContract.E_INTERNAL, e.toString());
        }
    }

    private static JSONObject help() {
        var json = BtProtocol.ok();
        try {
            json.put("service", "XpWeChatCall bluetooth trigger");
            json.put("service_uuid", BtProtocol.SERVICE_UUID.toString());
            json.put("rx_uuid", BtProtocol.RX_UUID.toString());
            json.put("tx_uuid", BtProtocol.TX_UUID.toString());
            json.put("auth", "every command must contain token");
            json.put("frame", "utf-8 json + \\n, max 1024 bytes");
            json.put("actions", new JSONArray()
                .put("call")
                .put("query")
                .put("health")
                .put("friends")
                .put("resolve")
                .put("ping")
                .put("help"));
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }
}
