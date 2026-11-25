package com.cdtubear.xpwechatcall.voip;

import android.content.Context;

import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.TriggerContract;

import org.json.JSONObject;

/**
 * 最近一次微信来电的状态
 *
 * 模块进程内共享：微信进程通过 Provider 回传，HTTP / BLE / 通知按钮写入接听命令，
 * 微信进程再轮询命令执行点击。只保留最近一通来电，避免无限增长。
 */
public final class VoipStore {

    private static final String TAG = "XpWeChatCall";
    private static final String PREFS = "voip_state";

    private static final String K_ID = "id";
    private static final String K_NAME = "name";
    private static final String K_TYPE = "type";
    private static final String K_STATE = "state";
    private static final String K_STARTED = "started_at";
    private static final String K_UPDATED = "updated_at";
    private static final String K_ANSWERED = "answered_at";
    private static final String K_DETAIL = "detail";
    private static final String K_COMMAND = "command";
    private static final String K_COMMAND_AT = "command_at";

    /** 响铃超过这个时间就认为已经错过，避免接口对着界面已消失的来电执行接听 */
    private static final long RING_TIMEOUT_MS = 75_000L;

    /** 命令有效期，超时未取走就作废 */
    private static final long COMMAND_TTL_MS = 30_000L;

    /** 事件保留时长，超过后不再对外展示 */
    private static final long KEEP_MS = 10 * 60_000L;

    private VoipStore() {
    }

    /**
     * 记录一通新来电，返回是否是新的通话（用于避免重复播报）
     */
    public static synchronized boolean onIncoming(Context context, String id, String name,
                                                  String type, long startedAt) {
        var voip = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        var existing = voip.getString(K_ID, "");
        var fresh = !id.equals(existing);
        voip.edit()
            .putString(K_ID, id)
            .putString(K_NAME, name)
            .putString(K_TYPE, type)
            .putString(K_STATE, TriggerContract.VS_RINGING)
            .putLong(K_STARTED, startedAt)
            .putLong(K_UPDATED, System.currentTimeMillis())
            .putLong(K_ANSWERED, 0L)
            .putString(K_DETAIL, "")
            .remove(K_COMMAND)
            .putLong(K_COMMAND_AT, 0L)
            .commit();
        LogHelp.i(TAG, "voip incoming id=" + id + " name=" + name + " type=" + type
            + " fresh=" + fresh);
        return fresh;
    }

    /**
     * 更新来电状态，id 不匹配说明是过期事件，直接忽略
     */
    public static synchronized void onState(Context context, String id, String state, String detail) {
        var voip = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!id.equals(voip.getString(K_ID, ""))) {
            LogHelp.w(TAG, "ignore stale voip state id=" + id);
            return;
        }
        var editor = voip.edit()
            .putString(K_STATE, state)
            .putLong(K_UPDATED, System.currentTimeMillis())
            .putString(K_DETAIL, detail == null ? "" : detail)
            .remove(K_COMMAND)
            .putLong(K_COMMAND_AT, 0L);
        if (TriggerContract.VS_ANSWERED.equals(state)) {
            editor.putLong(K_ANSWERED, System.currentTimeMillis());
        }
        editor.commit();
        LogHelp.i(TAG, "voip state id=" + id + " state=" + state);
    }

    /**
     * 请求接听当前来电，返回通话 id；没有正在响铃的来电时返回 null
     *
     * @param requested 调用方指定的 call_id，可为空
     */
    public static synchronized String requestAnswer(Context context, String requested) {
        var voip = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        var id = voip.getString(K_ID, "");
        var state = voip.getString(K_STATE, "");
        if (id.isEmpty() || !TriggerContract.VS_RINGING.equals(state)) {
            return null;
        }
        if (requested != null && !requested.isEmpty() && !requested.equals(id)) {
            return null;
        }
        var started = voip.getLong(K_STARTED, 0L);
        if (started <= 0 || System.currentTimeMillis() - started > RING_TIMEOUT_MS) {
            onState(context, id, TriggerContract.VS_MISSED, "ring timeout");
            return null;
        }
        voip.edit()
            .putString(K_COMMAND, TriggerContract.COMMAND_ANSWER)
            .putLong(K_COMMAND_AT, System.currentTimeMillis())
            .putLong(K_UPDATED, System.currentTimeMillis())
            .commit();
        LogHelp.i(TAG, "voip answer requested id=" + id);
        return id;
    }

    /**
     * 微信进程轮询命令，取走后立即清除，避免重复点击
     */
    public static synchronized String takeCommand(Context context, String id) {
        var voip = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!id.equals(voip.getString(K_ID, ""))) {
            return "";
        }
        if (!TriggerContract.VS_RINGING.equals(voip.getString(K_STATE, ""))) {
            return "";
        }
        var command = voip.getString(K_COMMAND, "");
        if (command == null || command.isEmpty()) {
            return "";
        }
        var at = voip.getLong(K_COMMAND_AT, 0L);
        if (at <= 0 || System.currentTimeMillis() - at > COMMAND_TTL_MS) {
            voip.edit().remove(K_COMMAND).putLong(K_COMMAND_AT, 0L).commit();
            return "";
        }
        voip.edit().remove(K_COMMAND)
            .putLong(K_COMMAND_AT, 0L)
            .putLong(K_UPDATED, System.currentTimeMillis())
            .commit();
        return command;
    }

    /**
     * 当前是否处于响铃状态
     */
    public static synchronized boolean isRinging(Context context) {
        var state = current(context);
        return state != null && TriggerContract.VS_RINGING.equals(state.optString("state", ""));
    }

    /**
     * 最近一通来电的完整信息，没有或过于久远时返回 null
     */
    public static synchronized JSONObject current(Context context) {
        var voip = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        var id = voip.getString(K_ID, "");
        if (id == null || id.isEmpty()) {
            return null;
        }
        var updated = voip.getLong(K_UPDATED, 0L);
        if (updated <= 0 || System.currentTimeMillis() - updated > KEEP_MS) {
            return null;
        }
        var state = voip.getString(K_STATE, "");
        var started = voip.getLong(K_STARTED, 0L);
        if (TriggerContract.VS_RINGING.equals(state) && started > 0
            && System.currentTimeMillis() - started > RING_TIMEOUT_MS) {
            onState(context, id, TriggerContract.VS_MISSED, "ring timeout");
            state = TriggerContract.VS_MISSED;
        }
        var json = new JSONObject();
        try {
            json.put("call_id", id);
            json.put("name", voip.getString(K_NAME, ""));
            json.put("type", voip.getString(K_TYPE, ""));
            json.put("state", state);
            json.put("started_at", started);
            json.put("updated_at", updated);
            json.put("answered_at", voip.getLong(K_ANSWERED, 0L));
            json.put("detail", voip.getString(K_DETAIL, ""));
            json.put("source", "wechat");
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }
}
