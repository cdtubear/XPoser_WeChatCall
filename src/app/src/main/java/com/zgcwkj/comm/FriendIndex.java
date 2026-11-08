package com.zgcwkj.comm;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * 本地好友索引：按昵称/备注查 wxid
 *
 * 数据来源是模块已经同步过的 wechat_friends.json，不实时访问微信数据库，
 * 查询快且不会因为微信版本变化影响接口稳定性。
 */
public final class FriendIndex {

    /** 好友数据超过该时间未更新时提示调用方 */
    private static final long STALE_MS = 7L * 24 * 60 * 60 * 1000;

    private FriendIndex() {
    }

    /**
     * 查询好友，返回 {count, stale, synced_at, candidates[]}
     *
     * @return 未同步过好友数据时返回 null
     */
    public static JSONObject resolve(Context context, String name, boolean fuzzy, int limit) {
        var raw = FriendSync.read(context);
        if (raw == null) {
            return null;
        }
        var keyword = normalize(name);
        var best = new LinkedHashMap<String, Candidate>();
        long syncedAt = 0L;
        try {
            var root = new JSONObject(raw);
            syncedAt = root.optLong(FriendSync.KEY_TIME, 0L);
            var array = root.optJSONArray(FriendSync.KEY_FRIENDS);
            if (array == null) {
                array = new JSONArray();
            }
            for (var i = 0; i < array.length(); i++) {
                var item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                var candidate = match(item, keyword, fuzzy);
                if (candidate == null) {
                    continue;
                }
                var old = best.get(candidate.wxid);
                if (old == null || candidate.score > old.score) {
                    best.put(candidate.wxid, candidate);
                }
            }
        } catch (Exception e) {
            LogHelp.e("XpWeChatCall", "resolve friend failed: " + e.getMessage(), e);
            return null;
        }
        if (syncedAt <= 0) {
            syncedAt = FriendSync.lastSyncTime(context);
        }

        var list = new ArrayList<>(best.values());
        list.sort(Comparator.comparingInt((Candidate c) -> c.score).reversed()
            .thenComparing(c -> c.display, String.CASE_INSENSITIVE_ORDER));

        var candidates = new JSONArray();
        var outputLimit = Math.max(1, Math.min(limit, 50));
        for (var i = 0; i < list.size() && i < outputLimit; i++) {
            var c = list.get(i);
            var item = new JSONObject();
            try {
                item.put("wxid", c.wxid);
                item.put("display", c.display);
                item.put("remark", c.remark);
                item.put("nickname", c.nickname);
                item.put("match", c.match);
                item.put("score", c.score);
            } catch (Exception ignored) {
                // 忽略单条异常
            }
            candidates.put(item);
        }

        var data = new JSONObject();
        try {
            data.put("count", list.size());
            data.put("candidates", candidates);
            data.put("synced_at", syncedAt);
            data.put("stale", syncedAt <= 0 || System.currentTimeMillis() - syncedAt > STALE_MS);
        } catch (Exception ignored) {
            // 忽略
        }
        return data;
    }

    /**
     * 获取好友列表，返回 {total, count, offset, synced_at, stale, friends[]}
     *
     * @param offset  起始下标，小于 0 按 0 处理
     * @param limit   返回数量，<= 0 表示不限制
     * @param keyword 可选关键字，匹配 wxid / 展示名 / 备注 / 昵称
     * @return 未同步过好友数据时返回 null
     */
    public static JSONObject list(Context context, int offset, int limit, String keyword) {
        var raw = FriendSync.read(context);
        if (raw == null) {
            return null;
        }
        var normalizedKeyword = normalize(keyword);
        var friends = new LinkedHashMap<String, JSONObject>();
        long syncedAt = 0L;
        try {
            var root = new JSONObject(raw);
            syncedAt = root.optLong(FriendSync.KEY_TIME, 0L);
            var array = root.optJSONArray(FriendSync.KEY_FRIENDS);
            if (array == null) {
                array = new JSONArray();
            }
            for (var i = 0; i < array.length(); i++) {
                var item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                var wxid = item.optString(FriendSync.KEY_USERNAME, "").trim();
                if (wxid.isEmpty() || friends.containsKey(wxid)) {
                    continue;
                }
                var remark = item.optString(FriendSync.KEY_REMARK, "").trim();
                var nickname = item.optString(FriendSync.KEY_NICKNAME, "").trim();
                var display = item.optString(FriendSync.KEY_NAME, "").trim();
                if (display.isEmpty()) {
                    display = !remark.isEmpty() ? remark : (!nickname.isEmpty() ? nickname : wxid);
                }
                if (!normalizedKeyword.isEmpty()
                    && !matchesKeyword(wxid, display, remark, nickname, normalizedKeyword)) {
                    continue;
                }
                var friend = new JSONObject();
                friend.put("wxid", wxid);
                friend.put("display", display);
                friend.put("remark", remark);
                friend.put("nickname", nickname);
                friends.put(wxid, friend);
            }
        } catch (Exception e) {
            LogHelp.e("XpWeChatCall", "list friends failed: " + e.getMessage(), e);
            return null;
        }
        if (syncedAt <= 0) {
            syncedAt = FriendSync.lastSyncTime(context);
        }

        var all = new ArrayList<>(friends.values());
        var total = all.size();
        var start = Math.max(0, offset);
        if (start > total) {
            start = total;
        }
        var end = total;
        if (limit > 0) {
            end = Math.min(total, start + limit);
        }
        var output = new JSONArray();
        for (var i = start; i < end; i++) {
            output.put(all.get(i));
        }

        var data = new JSONObject();
        try {
            data.put("total", total);
            data.put("count", output.length());
            data.put("offset", start);
            data.put("synced_at", syncedAt);
            data.put("stale", syncedAt <= 0 || System.currentTimeMillis() - syncedAt > STALE_MS);
            data.put("friends", output);
        } catch (Exception ignored) {
            // 忽略
        }
        return data;
    }

    private static boolean matchesKeyword(String wxid, String display, String remark,
                                          String nickname, String keyword) {
        return normalize(wxid).contains(keyword)
            || normalize(display).contains(keyword)
            || normalize(remark).contains(keyword)
            || normalize(nickname).contains(keyword);
    }

    /**
     * 好友数量，供健康检查使用
     */
    public static int count(Context context) {
        var raw = FriendSync.read(context);
        if (raw == null) {
            return 0;
        }
        try {
            var array = new JSONObject(raw).optJSONArray(FriendSync.KEY_FRIENDS);
            return array == null ? 0 : array.length();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 计算单条好友的匹配结果
     */
    private static Candidate match(JSONObject item, String keyword, boolean fuzzy) {
        if (keyword.isEmpty()) {
            return null;
        }
        var wxid = item.optString(FriendSync.KEY_USERNAME, "").trim();
        if (wxid.isEmpty()) {
            return null;
        }
        var remark = item.optString(FriendSync.KEY_REMARK, "").trim();
        var nickname = item.optString(FriendSync.KEY_NICKNAME, "").trim();
        var display = item.optString(FriendSync.KEY_NAME, "").trim();
        if (display.isEmpty()) {
            display = !remark.isEmpty() ? remark : (!nickname.isEmpty() ? nickname : wxid);
        }

        // 备注 > 微信昵称 > 展示名
        var score = 0;
        var match = "";
        if (!remark.isEmpty() && normalize(remark).equals(keyword)) {
            score = 100;
            match = "remark_exact";
        } else if (!nickname.isEmpty() && normalize(nickname).equals(keyword)) {
            score = 90;
            match = "nickname_exact";
        } else if (normalize(display).equals(keyword)) {
            score = 80;
            match = "name_exact";
        } else if (fuzzy) {
            if (startsWith(remark, keyword) || startsWith(nickname, keyword)
                || startsWith(display, keyword)) {
                score = 60;
                match = "startsWith";
            } else if (contains(remark, keyword) || contains(nickname, keyword)
                || contains(display, keyword)) {
                score = 40;
                match = "contains";
            }
        }
        if (score == 0) {
            return null;
        }
        return new Candidate(wxid, display, remark, nickname, match, score);
    }

    private static boolean startsWith(String value, String keyword) {
        return !value.isEmpty() && normalize(value).startsWith(keyword);
    }

    private static boolean contains(String value, String keyword) {
        return !value.isEmpty() && normalize(value).contains(keyword);
    }

    /**
     * 归一化：全角转半角、去零宽字符、合并空白、转小写
     */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        var sb = new StringBuilder(value.length());
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
                // 零宽字符，忽略
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static final class Candidate {
        final String wxid;
        final String display;
        final String remark;
        final String nickname;
        final String match;
        final int score;

        Candidate(String wxid, String display, String remark, String nickname,
                  String match, int score) {
            this.wxid = wxid;
            this.display = display;
            this.remark = remark;
            this.nickname = nickname;
            this.match = match;
            this.score = score;
        }
    }
}
