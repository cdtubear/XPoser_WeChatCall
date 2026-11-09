package com.zgcwkj.xpwechatcall.trigger;

import android.content.Context;
import android.content.SharedPreferences;

import com.zgcwkj.comm.TriggerContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 触发请求与结果存储
 *
 * 保存在应用私有的 SharedPreferences 中，模块进程被回收后仍可查询。
 * 所有方法都在类锁内执行，HTTP 线程与 Provider 线程不会互相覆盖。
 */
public final class TriggerStore {

    private static final String PREFS = "trigger_results";
    private static final String KEY_PREFIX = "req_";
    private static final long KEEP_MS = 30L * 60 * 1000;
    private static final int MAX_RECORDS = 200;

    private TriggerStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 创建请求；已存在相同 request_id 时直接返回旧记录
     */
    public static synchronized JSONObject create(Context context, String id, String wxid,
                                                  String type, int timeoutSec, String callback,
                                                  String source) {
        var existing = read(context, id);
        if (existing != null) {
            return existing;
        }
        prune(context);
        var now = System.currentTimeMillis();
        var record = new JSONObject();
        try {
            record.put("request_id", id);
            record.put("state", TriggerContract.S_ACCEPTED);
            record.put("wxid", wxid);
            record.put("type", type);
            record.put("created", now);
            record.put("updated", now);
            record.put("timeout_sec", timeoutSec);
            record.put("notified", false);
            record.put("callback", callback == null ? "" : callback);
            record.put("source", source == null ? "" : source);
            record.put("steps", new JSONArray().put(TriggerContract.S_ACCEPTED));
        } catch (Exception ignored) {
            // 忽略
        }
        prefs(context).edit().putString(KEY_PREFIX + id, record.toString()).commit();
        return record;
    }

    /**
     * 读取记录，不存在时返回 null
     */
    public static synchronized JSONObject get(Context context, String id) {
        return read(context, id);
    }

    /**
     * 当前是否有未结束的请求
     *
     * @return 正在进行的 request_id，没有则返回 null
     */
    public static synchronized String busyRequestId(Context context) {
        var now = System.currentTimeMillis();
        for (var record : all(context)) {
            var state = record.optString("state", "");
            if (TriggerContract.isTerminal(state)) {
                continue;
            }
            var timeout = record.optInt("timeout_sec", TriggerContract.DEF_TIMEOUT) * 1000L;
            var created = record.optLong("created", now);
            if (now - created <= timeout) {
                return record.optString("request_id", "");
            }
        }
        return null;
    }

    /**
     * 更新状态，只允许向前推进
     *
     * @return 更新后的记录，记录不存在时返回 null
     */
    public static synchronized JSONObject update(Context context, String id, String state,
                                                 String error, String detail) {
        var record = read(context, id);
        if (record == null) {
            return null;
        }
        var current = record.optString("state", "");
        if (TriggerContract.isTerminal(current)) {
            return record;
        }
        if (TriggerContract.S_FAILED.equals(state)) {
            // 失败可以直接覆盖进行中的状态
        } else if (TriggerContract.stateRank(state) <= TriggerContract.stateRank(current)) {
            return record;
        }
        try {
            record.put("state", state);
            record.put("updated", System.currentTimeMillis());
            if (error != null && !error.isEmpty()) {
                record.put("error", error);
            }
            if (detail != null && !detail.isEmpty()) {
                record.put("detail", detail);
            }
            var steps = record.optJSONArray("steps");
            if (steps == null) {
                steps = new JSONArray();
                record.put("steps", steps);
            }
            if (steps.length() == 0 || !state.equals(steps.optString(steps.length() - 1))) {
                steps.put(state);
            }
        } catch (Exception ignored) {
            // 忽略
        }
        write(context, id, record);
        return record;
    }

    /**
     * 单个请求超时检查
     *
     * @return 刚被标记为超时的记录，否则 null
     */
    public static synchronized JSONObject checkTimeout(Context context, String id) {
        var record = read(context, id);
        if (record == null || TriggerContract.isTerminal(record.optString("state", ""))) {
            return null;
        }
        if (!isTimedOut(record, System.currentTimeMillis())) {
            return null;
        }
        return update(context, id, TriggerContract.S_FAILED,
            TriggerContract.E_TIMEOUT, "no result before timeout");
    }

    /**
     * 扫描全部请求并标记超时
     */
    public static synchronized List<JSONObject> expireAll(Context context) {
        var list = new ArrayList<JSONObject>();
        var now = System.currentTimeMillis();
        for (var record : all(context)) {
            var state = record.optString("state", "");
            if (TriggerContract.isTerminal(state) || !isTimedOut(record, now)) {
                continue;
            }
            var id = record.optString("request_id", "");
            var updated = update(context, id, TriggerContract.S_FAILED,
                TriggerContract.E_TIMEOUT, "no result before timeout");
            if (updated != null) {
                list.add(updated);
            }
        }
        return list;
    }

    /**
     * 领取一次回调发送权，避免状态重复上报导致重复回调
     */
    public static synchronized JSONObject claimCallback(Context context, String id) {
        var record = read(context, id);
        if (record == null) {
            return null;
        }
        if (!TriggerContract.isTerminal(record.optString("state", ""))) {
            return null;
        }
        if (record.optBoolean("notified", false)) {
            return null;
        }
        var callback = record.optString("callback", "");
        if (callback.isEmpty()) {
            return null;
        }
        try {
            record.put("notified", true);
        } catch (Exception ignored) {
            // 忽略
        }
        write(context, id, record);
        return record;
    }

    private static boolean isTimedOut(JSONObject record, long now) {
        var timeout = record.optInt("timeout_sec", TriggerContract.DEF_TIMEOUT) * 1000L;
        var created = record.optLong("created", now);
        return timeout > 0 && now - created > timeout;
    }

    private static JSONObject read(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        var raw = prefs(context).getString(KEY_PREFIX + id, null);
        if (raw == null) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Context context, String id, JSONObject record) {
        prefs(context).edit().putString(KEY_PREFIX + id, record.toString()).commit();
    }

    private static List<JSONObject> all(Context context) {
        var list = new ArrayList<JSONObject>();
        for (var entry : prefs(context).getAll().entrySet()) {
            if (!entry.getKey().startsWith(KEY_PREFIX) || !(entry.getValue() instanceof String)) {
                continue;
            }
            try {
                list.add(new JSONObject((String) entry.getValue()));
            } catch (Exception ignored) {
                // 跳过损坏记录
            }
        }
        return list;
    }

    /**
     * 清理过期记录，避免 SharedPreferences 无限增长
     */
    private static void prune(Context context) {
        var now = System.currentTimeMillis();
        var editor = prefs(context).edit();
        var records = new ArrayList<JSONObject>();
        for (var entry : prefs(context).getAll().entrySet()) {
            if (!entry.getKey().startsWith(KEY_PREFIX) || !(entry.getValue() instanceof String)) {
                continue;
            }
            try {
                var record = new JSONObject((String) entry.getValue());
                var updated = record.optLong("updated", 0L);
                if (now - updated > KEEP_MS) {
                    editor.remove(entry.getKey());
                } else {
                    records.add(record);
                }
            } catch (Exception ignored) {
                editor.remove(entry.getKey());
            }
        }
        // 仍然过多时按更新时间淘汰最旧的记录
        if (records.size() > MAX_RECORDS) {
            records.sort((a, b) -> Long.compare(a.optLong("updated", 0L),
                b.optLong("updated", 0L)));
            var removeCount = records.size() - MAX_RECORDS;
            for (var i = 0; i < removeCount; i++) {
                editor.remove(KEY_PREFIX + records.get(i).optString("request_id", ""));
            }
        }
        editor.apply();
    }
}
