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
    private static final String K_SURFACE = "surface";
    private static final String K_COMMAND = "command";
    private static final String K_COMMAND_AT = "command_at";
    private static final String K_COMMAND_ID = "command_id";
    private static final String K_COMMAND_STATE = "command_state";
    private static final String K_COMMAND_CLAIMED_AT = "command_claimed_at";
    private static final String K_COMMAND_ACKED_AT = "command_acked_at";
    private static final String K_EPOCH = "epoch";

    private static final String CMD_ISSUED = "issued";
    private static final String CMD_CLAIMED = "claimed";
    private static final String CMD_ACKED = "acked";

    /** 微信进程领取命令后的租约；崩溃/重启后超过这个时间允许重新领取 */
    private static final long COMMAND_LEASE_MS = 6_000L;

    /** 命令领取结果，commandId 用于执行完成后的 ACK */
    public static final class Claim {
        public final String command;
        public final String commandId;

        Claim(String command, String commandId) {
            this.command = command == null ? "" : command;
            this.commandId = commandId == null ? "" : commandId;
        }
    }

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
        // 同一通电话的通知更新不能推进 epoch，否则会把正在执行的接听/挂断误判成旧命令
        var epoch = fresh ? voip.getLong(K_EPOCH, 0L) + 1 : voip.getLong(K_EPOCH, 0L);
        // 接听后微信会把通知改成“通话中”并继续刷新；最小化/Activity 销毁后
        // sAnswered 可能丢失，这里按已有状态兜底，绝不能把 ANSWERED 打回 RINGING。
        var previousState = voip.getString(K_STATE, "");
        var keepState = !fresh && !previousState.isEmpty()
            && !TriggerContract.VS_RINGING.equals(previousState)
            && !TriggerContract.VS_MISSED.equals(previousState);
        var editor = voip.edit()
            .putString(K_ID, id)
            .putString(K_NAME, name)
            .putString(K_TYPE, type)
            .putString(K_STATE, keepState ? previousState : TriggerContract.VS_RINGING)
            .putLong(K_STARTED, fresh ? startedAt : voip.getLong(K_STARTED, startedAt))
            .putLong(K_UPDATED, System.currentTimeMillis())
            .putLong(K_ANSWERED, keepState ? voip.getLong(K_ANSWERED, 0L) : 0L)
            .putString(K_DETAIL, keepState ? voip.getString(K_DETAIL, "") : "")
            .putLong(K_EPOCH, epoch);
        editor.putString(K_SURFACE, fresh ? "" : voip.getString(K_SURFACE, ""));
        if (fresh) {
            // 只有新来电才清掉上一通遗留的命令；同一通的“通话中”通知不能
            // 把正在等待领取的 answer/end 命令抹掉。
            editor.remove(K_COMMAND)
                .remove(K_COMMAND_ID)
                .remove(K_COMMAND_STATE)
                .putLong(K_COMMAND_AT, 0L);
        }
        editor.apply();
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
        var previous = voip.getString(K_STATE, "");
        if (isTerminal(previous) && !previous.equals(state)) {
            // 这通电话已经结束，微信通知/界面延迟刷新回来的 ANSWERED/MISSED
            // 都不能把最终状态翻回去
            LogHelp.w(TAG, "ignore voip state after " + previous + ": " + state);
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
        editor.apply();
        LogHelp.i(TAG, "voip state id=" + id + " state=" + state);
    }

    /**
     * 记录当前通话界面形态，仅用于诊断；不参与 call_id/状态机。
     */
    public static synchronized void onSurface(Context context, String id, String surface) {
        var voip = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!id.equals(voip.getString(K_ID, ""))) {
            return;
        }
        var clean = surface == null ? "" : surface.trim();
        if (!"full".equals(clean) && !"banner".equals(clean) && !"mini".equals(clean)) {
            return;
        }
        voip.edit().putString(K_SURFACE, clean).apply();
    }

    private static boolean isTerminal(String state) {
        return TriggerContract.VS_ENDED.equals(state)
            || TriggerContract.VS_MISSED.equals(state)
            || TriggerContract.VS_REJECTED.equals(state);
    }

    /**
     * 请求接听当前来电，返回通话 id；没有正在响铃的来电时返回 null
     *
     * @param requested 调用方指定的 call_id，可为空
     */
    public static synchronized String requestAnswer(Context context, String requested) {
        return requestCommand(context, requested, TriggerContract.COMMAND_ANSWER,
            TriggerContract.VS_RINGING);
    }

    /** 拒接正在响铃的来电 */
    public static synchronized String requestReject(Context context, String requested) {
        return requestCommand(context, requested, TriggerContract.COMMAND_REJECT,
            TriggerContract.VS_RINGING);
    }

    /** 挂断已经接通的通话 */
    public static synchronized String requestHangup(Context context, String requested) {
        return requestCommand(context, requested, TriggerContract.COMMAND_HANGUP,
            TriggerContract.VS_ANSWERED);
    }

    private static synchronized String requestCommand(Context context, String requested,
                                                       String command, String requiredState) {
        var voip = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        var id = voip.getString(K_ID, "");
        var state = voip.getString(K_STATE, "");
        if (id.isEmpty() || !requiredState.equals(state)) {
            return null;
        }
        if (requested != null && !requested.isEmpty() && !requested.equals(id)) {
            return null;
        }
        if (TriggerContract.VS_RINGING.equals(requiredState)) {
            var started = voip.getLong(K_STARTED, 0L);
            if (started <= 0 || System.currentTimeMillis() - started > RING_TIMEOUT_MS) {
                onState(context, id, TriggerContract.VS_MISSED, "ring timeout");
                return null;
            }
        }
        var now = System.currentTimeMillis();
        if (command.equals(voip.getString(K_COMMAND, ""))
            && now - voip.getLong(K_COMMAND_AT, 0L) < 1_500L) {
            LogHelp.i(TAG, "voip " + command + " duplicate ignored id=" + id);
            return id;
        }
        voip.edit()
            .putString(K_COMMAND, command)
            .putString(K_COMMAND_ID, "cmd_" + now + "_"
                + Integer.toHexString(new java.security.SecureRandom().nextInt() & 0xFFFFFF))
            .putString(K_COMMAND_STATE, CMD_ISSUED)
            .putLong(K_COMMAND_AT, now)
            .putLong(K_COMMAND_CLAIMED_AT, 0L)
            .putLong(K_COMMAND_ACKED_AT, 0L)
            .putLong(K_UPDATED, now)
            .apply();
        LogHelp.i(TAG, "voip " + command + " requested id=" + id);
        return id;
    }

    /** 最近一次已入队命令的 command_id，供 202 响应和诊断页展示 */
    public static synchronized String lastCommandId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_COMMAND_ID, "");
    }

    public static synchronized long commandEpoch(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(K_EPOCH, 0L);
    }

    /**
     * 微信进程领取命令。命令不会立刻删除，而是进入 claimed 租约状态：
     * 执行完必须 ackCommand；进程崩溃/重启后租约到期可重新领取，避免丢命令。
     */
    public static synchronized Claim claimCommand(Context context, String id) {
        var voip = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!id.equals(voip.getString(K_ID, ""))) {
            return null;
        }
        var current = voip.getString(K_STATE, "");
        if (!TriggerContract.VS_RINGING.equals(current)
            && !TriggerContract.VS_ANSWERED.equals(current)) {
            return null;
        }
        var command = voip.getString(K_COMMAND, "");
        if (command == null || command.isEmpty()) {
            return null;
        }
        var now = System.currentTimeMillis();
        var at = voip.getLong(K_COMMAND_AT, 0L);
        if (at <= 0 || now - at > COMMAND_TTL_MS) {
            voip.edit().remove(K_COMMAND)
                .putString(K_COMMAND_STATE, CMD_ACKED)
                .putLong(K_COMMAND_AT, 0L)
                .apply();
            return null;
        }
        var state = voip.getString(K_COMMAND_STATE, CMD_ISSUED);
        var claimedAt = voip.getLong(K_COMMAND_CLAIMED_AT, 0L);
        if (CMD_CLAIMED.equals(state) && now - claimedAt < COMMAND_LEASE_MS) {
            // 另一条执行链正在处理，避免同一命令被点两次
            return null;
        }
        if (CMD_ACKED.equals(state)) {
            return null;
        }
        var commandId = voip.getString(K_COMMAND_ID, "");
        if (commandId.isEmpty()) {
            commandId = "cmd_" + now + "_"
                + Integer.toHexString(new java.security.SecureRandom().nextInt() & 0xFFFFFF);
        }
        voip.edit()
            .putString(K_COMMAND_STATE, CMD_CLAIMED)
            .putString(K_COMMAND_ID, commandId)
            .putLong(K_COMMAND_CLAIMED_AT, now)
            .putLong(K_UPDATED, now)
            .apply();
        LogHelp.i(TAG, "voip command claimed id=" + id + " command=" + command
            + " cmd=" + commandId + " lease=" + COMMAND_LEASE_MS);
        return new Claim(command, commandId);
    }

    /**
     * 微信进程确认命令已执行完（无论成功失败）。只有 command_id 匹配时才清理，
     * 防止旧执行链误 ACK 掉新命令。
     */
    public static synchronized boolean ackCommand(Context context, String id,
                                                   String commandId) {
        var voip = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!id.equals(voip.getString(K_ID, ""))
            || commandId == null || commandId.isEmpty()
            || !commandId.equals(voip.getString(K_COMMAND_ID, ""))) {
            return false;
        }
        var now = System.currentTimeMillis();
        voip.edit()
            .remove(K_COMMAND)
            .putString(K_COMMAND_STATE, CMD_ACKED)
            .putLong(K_COMMAND_AT, 0L)
            .putLong(K_COMMAND_ACKED_AT, now)
            .putLong(K_UPDATED, now)
            .apply();
        LogHelp.i(TAG, "voip command acked id=" + id + " cmd=" + commandId);
        return true;
    }

    /** 兼容旧调用：只领取命令文本，不负责 ACK（新代码请用 claimCommand） */
    public static synchronized String takeCommand(Context context, String id) {
        var claim = claimCommand(context, id);
        return claim == null ? "" : claim.command;
    }

    /**
     * 当前是否处于响铃状态
     */
    public static synchronized boolean isRinging(Context context) {
        var state = current(context);
        return state != null && TriggerContract.VS_RINGING.equals(state.optString("state", ""));
    }

    public static boolean isFinished(String state) {
        return isTerminal(state);
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
            json.put("surface", voip.getString(K_SURFACE, ""));
            json.put("command", voip.getString(K_COMMAND, ""));
            json.put("command_id", voip.getString(K_COMMAND_ID, ""));
            json.put("command_state", voip.getString(K_COMMAND_STATE, ""));
            json.put("command_at", voip.getLong(K_COMMAND_AT, 0L));
            json.put("command_claimed_at", voip.getLong(K_COMMAND_CLAIMED_AT, 0L));
            json.put("command_acked_at", voip.getLong(K_COMMAND_ACKED_AT, 0L));
            json.put("epoch", voip.getLong(K_EPOCH, 0L));
            json.put("source", "wechat");
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }
}
