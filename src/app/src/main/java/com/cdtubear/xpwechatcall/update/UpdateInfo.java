package com.cdtubear.xpwechatcall.update;

import org.json.JSONObject;

/**
 * GitHub Release 中的一条版本信息
 *
 * 只保留检查更新需要的字段：版本号、更新说明、发布页和 APK 附件。
 */
public final class UpdateInfo {

    /** 原始 tag，例如 v1.1.3 */
    public final String tag;
    /** 去掉 v 前缀、只保留数字点的版本号，例如 1.1.3 */
    public final String version;
    /** Release 标题 */
    public final String name;
    /** Release 说明（Markdown 原文） */
    public final String notes;
    /** Release 页面地址 */
    public final String htmlUrl;
    /** APK 下载地址，没有附件时为空 */
    public final String apkUrl;
    /** APK 文件名 */
    public final String apkName;
    /** APK 字节数，未知时为 0 */
    public final long apkSize;
    /** 发布时间戳（毫秒），未知时为 0 */
    public final long publishedAt;

    UpdateInfo(String tag, String version, String name, String notes, String htmlUrl,
               String apkUrl, String apkName, long apkSize, long publishedAt) {
        this.tag = tag == null ? "" : tag;
        this.version = version == null ? "" : version;
        this.name = name == null ? "" : name;
        this.notes = notes == null ? "" : notes;
        this.htmlUrl = htmlUrl == null ? "" : htmlUrl;
        this.apkUrl = apkUrl == null ? "" : apkUrl;
        this.apkName = apkName == null ? "" : apkName;
        this.apkSize = apkSize;
        this.publishedAt = publishedAt;
    }

    /**
     * 是否带有可下载的 APK
     */
    public boolean hasApk() {
        return !apkUrl.isEmpty();
    }

    /**
     * 序列化到本地缓存，说明文字截断避免 SharedPreferences 过大
     */
    public JSONObject toJson() {
        var json = new JSONObject();
        try {
            json.put("tag", tag);
            json.put("version", version);
            json.put("name", name);
            var body = notes.length() > 1500 ? notes.substring(0, 1500) : notes;
            json.put("notes", body);
            json.put("html_url", htmlUrl);
            json.put("apk_url", apkUrl);
            json.put("apk_name", apkName);
            json.put("apk_size", apkSize);
            json.put("published_at", publishedAt);
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }

    /**
     * 从本地缓存还原，字段缺失时使用空值
     */
    public static UpdateInfo fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new UpdateInfo(
            json.optString("tag", ""),
            json.optString("version", ""),
            json.optString("name", ""),
            json.optString("notes", ""),
            json.optString("html_url", ""),
            json.optString("apk_url", ""),
            json.optString("apk_name", ""),
            json.optLong("apk_size", 0L),
            json.optLong("published_at", 0L));
    }
}
