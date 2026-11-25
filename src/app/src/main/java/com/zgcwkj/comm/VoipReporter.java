package com.zgcwkj.comm;

import android.content.Context;

import org.json.JSONObject;

/**
 * 微信进程 -> 模块进程的来电回传与命令轮询
 *
 * 只在微信进程中使用。回传走同步 binder 调用，保证"先上报来电，再开始轮询命令"的顺序。
 */
public final class VoipReporter {

    private static final String TAG = "XpWeChatCall";

    private VoipReporter() {
    }

    /**
     * 上报一通来电
     */
    public static void reportIncoming(Context context, String callId, String name,
                                      boolean video, long startedAt) {
        var json = new JSONObject();
        try {
            json.put("call_id", callId);
            json.put("name", name);
            json.put("type", video ? TriggerContract.CALL_VIDEO : TriggerContract.CALL_VOICE);
            json.put("started_at", startedAt);
        } catch (Exception e) {
            return;
        }
        call(context, TriggerContract.METHOD_VOIP_INCOMING, json.toString());
    }

    /**
     * 上报接听 / 错过 / 结束
     */
    public static void reportState(Context context, String callId, String state, String detail) {
        var json = new JSONObject();
        try {
            json.put("call_id", callId);
            json.put("state", state);
            if (detail != null) {
                json.put("detail", detail);
            }
        } catch (Exception e) {
            return;
        }
        call(context, TriggerContract.METHOD_VOIP_STATE, json.toString());
    }

    /**
     * 轮询是否有接听命令，返回 answer 或空串
     */
    public static String pollCommand(Context context, String callId) {
        if (context == null || callId == null || callId.isEmpty()) {
            return "";
        }
        try {
            var result = context.getApplicationContext().getContentResolver().call(
                TriggerContract.CONTENT_URI, TriggerContract.METHOD_VOIP_POLL, callId, null);
            return result == null ? "" : result.getString("command", "");
        } catch (Throwable e) {
            LogHelp.w(TAG, "poll voip command failed: " + e.getMessage());
            return "";
        }
    }

    private static void call(Context context, String method, String payload) {
        if (context == null) {
            return;
        }
        try {
            context.getApplicationContext().getContentResolver().call(
                TriggerContract.CONTENT_URI, method, payload, null);
        } catch (Throwable e) {
            LogHelp.w(TAG, "voip report failed method=" + method + ": " + e.getMessage());
        }
    }
}
