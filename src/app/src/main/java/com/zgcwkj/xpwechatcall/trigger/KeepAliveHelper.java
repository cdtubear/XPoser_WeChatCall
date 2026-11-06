package com.zgcwkj.xpwechatcall.trigger;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 保活辅助：电池优化白名单、MIUI / HyperOS 自启动设置入口
 */
public final class KeepAliveHelper {

    private static final ComponentName MIUI_AUTOSTART = new ComponentName(
        "com.miui.securitycenter",
        "com.miui.permcenter.autostart.AutoStartManagementActivity");

    private KeepAliveHelper() {
    }

    /**
     * 是否已忽略电池优化（进入 Doze 白名单）
     */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        var manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * 使用 root 静默加入 Doze 白名单，并设置为 active standby bucket
     *
     * @return false 表示没有 root 或命令执行失败，需要回退到系统弹窗
     */
    public static boolean applyBatteryWhitelistWithRoot(Context context) {
        if (!RootShell.hasRoot(context)) {
            return false;
        }
        var pkg = context.getPackageName();
        var result = RootShell.runAsRoot(
            "cmd deviceidle whitelist +" + pkg + " >/dev/null 2>&1; "
                + "am set-standby-bucket " + pkg + " active >/dev/null 2>&1; "
                + "echo xp_done");
        return result.ok && result.output.contains("xp_done");
    }

    /**
     * 请求用户把 App 加入电池优化白名单
     */
    public static void requestIgnoreBatteryOptimizations(Activity activity) {
        if (isIgnoringBatteryOptimizations(activity)) {
            return;
        }
        try {
            activity.startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + activity.getPackageName())));
            return;
        } catch (Throwable ignored) {
            // 部分 ROM 没有该页面，回退到电池优化列表
        }
        try {
            activity.startActivity(new Intent(
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable ignored) {
            openAppDetails(activity);
        }
    }

    /**
     * 打开自启动设置；MIUI / HyperOS 直接跳到安全中心的自启动管理
     */
    public static void openAutoStartSettings(Activity activity) {
        try {
            activity.startActivity(new Intent().setComponent(MIUI_AUTOSTART));
            return;
        } catch (Throwable ignored) {
            // 非 MIUI 或组件被移除
        }
        try {
            activity.startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 打开应用详情页，作为所有跳转的最后回退
     */
    public static void openAppDetails(Activity activity) {
        try {
            activity.startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
