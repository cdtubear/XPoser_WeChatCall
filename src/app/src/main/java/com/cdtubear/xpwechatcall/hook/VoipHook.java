package com.cdtubear.xpwechatcall.hook;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import com.zgcwkj.comm.LogHelp;
import com.cdtubear.xpwechatcall.voip.IncomingCallMonitor;
import com.cdtubear.xpwechatcall.voip.IncomingCallUi;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信来电 Hook
 *
 * 挂钩微信音视频通话界面（VideoActivity），在页面恢复时让 IncomingCallMonitor
 * 扫描界面、判断是来电还是自己发起的通话，并轮询接听命令。
 */
public final class VoipHook {

    private static final String TAG = "XpWeChatCall";

    /** 微信 1:1 音视频通话界面 */
    private static final String VIDEO_ACTIVITY = "com.tencent.mm.plugin.voip.ui.VideoActivity";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private VoipHook() {
    }

    public static void install(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Class<?> clazz;
        try {
            clazz = XposedHelpers.findClass(VIDEO_ACTIVITY, loader);
        } catch (Throwable e) {
            log("voip hook skipped: " + e);
            return;
        }
        // 每个生命周期单独挂，某个方法不存在时不能影响其它 Hook
        hookLifecycle(clazz, "onCreate", new Class<?>[]{android.os.Bundle.class},
            (activity, param) -> dispatch(activity, "onCreate"));
        hookLifecycle(clazz, "onStart", new Class<?>[0],
            (activity, param) -> dispatch(activity, "onStart"));
        hookLifecycle(clazz, "onResume", new Class<?>[0],
            (activity, param) -> dispatch(activity, "onResume"));
        hookLifecycle(clazz, "onNewIntent", new Class<?>[]{Intent.class},
            (activity, param) -> dispatch(activity, "onNewIntent"));
        hookLifecycle(clazz, "onDestroy", new Class<?>[0],
            (activity, param) -> IncomingCallMonitor.onDestroy(activity));
        log("hooked " + VIDEO_ACTIVITY);
        hookCallStart(loader);
        hookNotifications();
        // 进程启动/冷启动接电话时，Activity 可能已经 resume 完，补扫一次当前实例
        scanCurrentActivities(loader);
    }

    /**
     * 微信原生的通话入口：NewVoipMgr.startVoip(Context, userName, isOutCall, isVideoCall, ...)
     *
     * 这是唯一能直接拿到联系人、来/去电、视频/语音三要素的地方，
     * 且比 VideoActivity 更早执行，用来弥补 Flutter 界面读不到文字的问题。
     */
    private static void hookCallStart(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.plugin.voip.model.d0", loader, "f0",
                Context.class, String.class, boolean.class, boolean.class, long.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            var user = (String) param.args[1];
                            var outgoing = Boolean.TRUE.equals(param.args[2]);
                            var video = Boolean.TRUE.equals(param.args[3]);
                            log("voip start user=" + user + " outgoing=" + outgoing
                                + " video=" + video);
                            if (!outgoing) {
                                IncomingCallMonitor.noteNativeIncoming(user, video);
                            }
                        } catch (Throwable e) {
                            log("voip start parse failed: " + e);
                        }
                    }
                });
            log("hooked voip start");
        } catch (Throwable e) {
            log("voip start hook skipped: " + e);
        }
        // 来电振铃入口：d0.p(boolean, boolean, String) 在铃声响起时调用，
        // 此时 d0.p=联系人、d0.r=是否去电、d0.s=是否视频
        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.plugin.voip.model.d0", loader, "p",
                boolean.class, boolean.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            var self = param.thisObject;
                            var user = (String) XposedHelpers.getObjectField(self, "p");
                            var outgoing = XposedHelpers.getBooleanField(self, "r");
                            var video = XposedHelpers.getBooleanField(self, "s");
                            log("voip ring user=" + user + " outgoing=" + outgoing
                                + " video=" + video);
                            if (!outgoing) {
                                if (user != null && !user.isEmpty()) {
                                    IncomingCallMonitor.noteNativeIncoming(user, video);
                                } else {
                                    // 个别版本振铃时还没填联系人，稍后再读一次
                                    retryRingUser(self, video, 0);
                                }
                            }
                        } catch (Throwable e) {
                            log("voip ring parse failed: " + e);
                        }
                    }
                });
            log("hooked voip ring");
        } catch (Throwable e) {
            log("voip ring hook skipped: " + e);
        }
    }

    private interface LifecycleAction {
        void run(Activity activity, XC_MethodHook.MethodHookParam param);
    }

    private static void hookLifecycle(Class<?> clazz, String name,
                                      Class<?>[] parameterTypes,
                                      LifecycleAction action) {
        try {
            var method = findExactMethod(clazz, name, parameterTypes);
            if (method == null) {
                log("voip hook missing: " + name);
                return;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    action.run((Activity) param.thisObject, param);
                }
            });
        } catch (Throwable e) {
            // 单个 Hook 失败不影响其它生命周期
            log("voip hook " + name + " failed: " + e);
        }
    }

    private static java.lang.reflect.Method findExactMethod(Class<?> clazz, String name,
                                                            Class<?>[] parameterTypes) {
        for (var type = clazz; type != null; type = type.getSuperclass()) {
            for (var method : type.getDeclaredMethods()) {
                if (method.getName().equals(name)
                    && java.util.Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static volatile long sLastMgrDump;

    /**
     * 打印微信 VoipMgr 单例的字符串/布尔字段，定位来电人到底存在哪个字段
     * （Flutter 界面没有文字，只能从原生对象取）
     */
    private static void dumpVoipMgr(ClassLoader loader) {
        if (System.currentTimeMillis() - sLastMgrDump < 10_000L) {
            return;
        }
        sLastMgrDump = System.currentTimeMillis();
        try {
            var mgrClass = XposedHelpers.findClass(
                "com.tencent.mm.plugin.voip.model.d0", loader);
            var holderClass = XposedHelpers.findClass("sy4.v", loader);
            Object instance = null;
            for (var field : holderClass.getDeclaredFields()) {
                try {
                    if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    var value = field.get(null);
                    if (value != null && mgrClass.isInstance(value)) {
                        instance = value;
                        break;
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            if (instance == null) {
                log("voip mgr instance not found");
                return;
            }
            var builder = new StringBuilder("voip mgr fields: ");
            for (var field : mgrClass.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    var value = field.get(instance);
                    if (value instanceof String) {
                        var text = (String) value;
                        if (!text.isEmpty()) {
                            if (text.length() > 40) {
                                text = text.substring(0, 40);
                            }
                            builder.append(field.getName()).append('=').append(text).append("; ");
                        }
                    } else if (value instanceof Boolean && (Boolean) value) {
                        builder.append(field.getName()).append("=true; ");
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            log(builder.toString());
        } catch (Throwable e) {
            log("voip mgr dump failed: " + e);
        }
    }

    private static void dispatch(Activity activity, String where) {
        if (activity == null) {
            return;
        }
        log("voip activity " + where + ": " + activity.getClass().getName());
        if ("onCreate".equals(where) || "onNewIntent".equals(where)) {
            logIntent(activity, where);
        }
        if ("onCreate".equals(where) || "onResume".equals(where)) {
            scanActiveNotifications(activity);
            dumpVoipMgr(activity.getClassLoader());
        }
        IncomingCallMonitor.onResume(activity);
    }

    /**
     * 打印启动 Intent 的 extra key，便于确认微信用哪个字段区分来/去电
     */
    /**
     * 微信来电通知（包括非全屏、只在通知栏提示的情况）
     *
     * 通知标题通常就是联系人名，正文是「邀请你视频通话」，
     * 这条比 VideoActivity 早，也覆盖了不打开全屏界面的来电。
     */
    private static void hookNotifications() {
        try {
            XposedBridge.hookAllMethods(NotificationManager.class, "notify",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        handleNotificationArgs(param.args);
                    }
                });
            XposedBridge.hookAllMethods(NotificationManager.class, "notifyAsPackage",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        handleNotificationArgs(param.args);
                    }
                });
            // 来电常走 VoipService.startForeground，不经过 notify
            XposedBridge.hookAllMethods(Service.class, "startForeground",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        handleNotificationArgs(param.args);
                    }
                });
            log("hooked wechat notifications");
        } catch (Throwable e) {
            log("notification hook skipped: " + e);
        }
    }

    private static void handleNotificationArgs(Object[] args) {
        try {
            Notification notification = null;
            for (var arg : args) {
                if (arg instanceof Notification) {
                    notification = (Notification) arg;
                    break;
                }
            }
            if (notification != null) {
                handleNotification(notification, true);
            }
        } catch (Throwable e) {
            log("notification parse failed: " + e);
        }
    }

    /** 界面打开时补读当前活动通知，防止通知 Hook 安装晚于通知发出 */
    private static void scanActiveNotifications(Activity activity) {
        try {
            var manager = (NotificationManager) activity
                .getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            for (var sbn : manager.getActiveNotifications()) {
                if (sbn != null) {
                    handleNotification(sbn.getNotification(), false);
                }
            }
        } catch (Throwable e) {
            log("active notifications failed: " + e);
        }
    }

    private static void handleNotification(Notification notification, boolean report) {
        var extras = notification.extras;
        if (extras == null) {
            return;
        }
        var title = textOf(extras.getCharSequence(Notification.EXTRA_TITLE));
        var bigTitle = textOf(extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
        var text = textOf(extras.getCharSequence(Notification.EXTRA_TEXT));
        var bigText = textOf(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        var sub = textOf(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        var info = textOf(extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
        var all = title + " | " + bigTitle + " | " + text + " | " + bigText
            + " | " + sub + " | " + info;
        var channelId = notification.getChannelId();
        var voipChannel = channelId != null
            && (channelId.contains("voip") || channelId.contains("VoIP"));
        // 通话中提醒：微信用 reminder_channel_id，标题就是联系人，正文是「视频/语音通话中」
        var reminderCall = "reminder_channel_id".equals(channelId)
            && (text.contains("视频通话中") || text.contains("语音通话中"))
            && !title.isEmpty() && !title.equals("微信");
        if (all.contains("通话") || all.contains("邀请")) {
            log("notif probe channel=" + channelId + " cat="
                + notification.category + " :: " + all);
        }
        var callLike = voipChannel || reminderCall
            || notification.category == Notification.CATEGORY_CALL
            || (all.contains("邀请你")
                && (all.contains("视频通话") || all.contains("语音通话")));
        if (!callLike) {
            return;
        }
        // 非通话频道的「通话中」多为聊天里的系统提示，不能当来电
        if (!voipChannel && !reminderCall && (all.contains("通话中")
            || all.contains("已接通") || all.contains("正在通话"))) {
            return;
        }
        if (reminderCall && IncomingCallUi.isOutgoingRecent()) {
            return;
        }
        var name = pickNotificationName(title, bigTitle, text, bigText, sub, info);
        var video = all.contains("视频");
        log("wechat call notification name=" + name + " video=" + video
            + " channel=" + channelId + " :: " + all);
        if (name.isEmpty()) {
            return;
        }
        if (IncomingCallMonitor.hasActiveSession()) {
            IncomingCallMonitor.noteNativeName(name, video);
            return;
        }
        if (report) {
            IncomingCallMonitor.noteNotificationIncoming(
                AndroidAppHelper.currentApplication(), name, video);
        } else {
            IncomingCallMonitor.noteNativeName(name, video);
        }
    }

    private static String textOf(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String pickNotificationName(String title, String bigTitle,
                                               String text, String bigText,
                                               String sub, String info) {
        for (var value : new String[]{text, bigText, sub, info}) {
            var at = value.indexOf("邀请你");
            if (at > 0) {
                return clean(value.substring(0, at));
            }
        }
        for (var value : new String[]{title, bigTitle}) {
            var cleaned = clean(value);
            if (!cleaned.isEmpty() && !cleaned.contains("通话")
                && !cleaned.equals("微信")) {
                return cleaned;
            }
        }
        for (var value : new String[]{text, bigText, sub, info}) {
            var cleaned = clean(value);
            if (!cleaned.isEmpty() && !cleaned.contains("通话")
                && !cleaned.contains("邀请")) {
                return cleaned;
            }
        }
        return "";
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        var cleaned = value.replace("微信", "").replace("视频", "")
            .replace("语音", "").replace("邀请你", "")
            .replace("通话", "").replace("：", "").replace(":", "")
            .trim();
        return cleaned.length() > 20 ? "" : cleaned;
    }

    private static void retryRingUser(Object mgr, boolean video, int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                var user = (String) XposedHelpers.getObjectField(mgr, "p");
                if (user != null && !user.isEmpty()) {
                    log("voip ring user(late)=" + user);
                    IncomingCallMonitor.noteNativeIncoming(user, video);
                } else if (attempt < 3) {
                    retryRingUser(mgr, video, attempt + 1);
                }
            } catch (Throwable e) {
                log("voip ring user retry failed: " + e);
            }
        }, 800);
    }

    private static void logIntent(Activity activity, String where) {
        try {
            var intent = activity.getIntent();
            if (intent == null) {
                return;
            }
            var extras = intent.getExtras();
            var detail = new StringBuilder("null");
            if (extras != null) {
                detail.setLength(0);
                for (var key : extras.keySet()) {
                    Object value;
                    try {
                        value = extras.get(key);
                    } catch (Throwable e) {
                        value = "<" + e.getClass().getSimpleName() + ">";
                    }
                    var text = String.valueOf(value);
                    if (text.length() > 60) {
                        text = text.substring(0, 60) + "...";
                    }
                    detail.append(key).append('=').append(text).append("; ");
                }
            }
            log(where + " intent action=" + intent.getAction()
                + " extras=[" + detail + "]");
        } catch (Throwable e) {
            log(where + " intent log failed: " + e);
        }
    }

    /**
     * 反射 ActivityThread.mActivities，找到已经存在的通话界面。
     * Hook 安装晚于 Activity.onResume 时（例如微信冷启动直接进来电），靠这一步兜底。
     */
    private static void scanCurrentActivities(ClassLoader loader) {
        try {
            var activityThread = XposedHelpers.findClass(
                "android.app.ActivityThread", loader);
            var current = XposedHelpers.callStaticMethod(activityThread,
                "currentActivityThread");
            if (current == null) {
                return;
            }
            var records = XposedHelpers.getObjectField(current, "mActivities");
            var size = (int) XposedHelpers.callMethod(records, "size");
            for (var i = 0; i < size; i++) {
                var record = XposedHelpers.callMethod(records, "valueAt", i);
                var activity = XposedHelpers.getObjectField(record, "activity");
                if (activity instanceof Activity && isVideoActivity(activity.getClass())) {
                    log("scan existing voip activity: " + activity.getClass().getName());
                    IncomingCallMonitor.onResume((Activity) activity);
                }
            }
        } catch (Throwable e) {
            log("scan existing voip activity failed: " + e);
        }
    }

    private static boolean isVideoActivity(Class<?> clazz) {
        var current = clazz;
        while (current != null) {
            if (VIDEO_ACTIVITY.equals(current.getName())) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static void log(String message) {
        LogHelp.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
