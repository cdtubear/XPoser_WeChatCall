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
     * 领取命令结果，commandId 用于执行完成后 ACK。
     */
    public static final class Polled {
        public final String command;
        public final String commandId;

        Polled(String command, String commandId) {
            this.command = command == null ? "" : command;
            this.commandId = commandId == null ? "" : commandId;
        }
    }

    /**
     * 领取接听/挂断命令。命令带 6 秒租约，执行完必须 ackCommand。
     */
    public static Polled pollCommand(Context context, String callId) {
        if (context == null || callId == null || callId.isEmpty()) {
            return new Polled("", "");
        }
        try {
            var result = context.getApplicationContext().getContentResolver().call(
                TriggerContract.CONTENT_URI, TriggerContract.METHOD_VOIP_POLL, callId, null);
            if (result == null) {
                return new Polled("", "");
            }
            return new Polled(result.getString("command", ""),
                result.getString("command_id", ""));
        } catch (Throwable e) {
            LogHelp.w(TAG, "poll voip command failed: " + e.getMessage());
            return new Polled("", "");
        }
    }

    /**
     * 上报当前通话界面形态（full / banner / mini），仅用于诊断。
     */
    public static void reportSurface(Context context, String callId, String surface) {
        if (context == null || callId == null || callId.isEmpty()
            || surface == null || surface.isEmpty()) {
            return;
        }
        var json = new JSONObject();
        try {
            json.put("call_id", callId);
            json.put("surface", surface);
        } catch (Exception e) {
            return;
        }
        try {
            context.getApplicationContext().getContentResolver().call(
                TriggerContract.CONTENT_URI, TriggerContract.METHOD_VOIP_SURFACE,
                json.toString(), null);
        } catch (Throwable e) {
            LogHelp.w(TAG, "report voip surface failed: " + e.getMessage());
        }
    }

    /**
     * 执行完接听/拒接/挂断后确认命令；失败也要 ACK，避免无限重领。
     */
    public static void ackCommand(Context context, String callId, String commandId) {
        if (context == null || callId == null || callId.isEmpty()
            || commandId == null || commandId.isEmpty()) {
            return;
        }
        var json = new JSONObject();
        try {
            json.put("call_id", callId);
            json.put("command_id", commandId);
        } catch (Exception e) {
            return;
        }
        try {
            context.getApplicationContext().getContentResolver().call(
                TriggerContract.CONTENT_URI, TriggerContract.METHOD_VOIP_ACK,
                json.toString(), null);
        } catch (Throwable e) {
            LogHelp.w(TAG, "ack voip command failed: " + e.getMessage());
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
