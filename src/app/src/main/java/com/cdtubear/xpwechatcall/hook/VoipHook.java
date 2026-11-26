package com.cdtubear.xpwechatcall.hook;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.TriggerContract;
import com.zgcwkj.comm.VoipReporter;
import com.cdtubear.xpwechatcall.voip.IncomingCallMonitor;
import com.cdtubear.xpwechatcall.voip.IncomingCallUi;

import java.lang.ref.WeakReference;

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
        hookOverlayWindows();
        hookAudioMode();
        // 进程启动/冷启动接电话时，Activity 可能已经 resume 完，补扫一次当前实例
        scanCurrentActivities(loader);
        // 常驻接听轮询：不依赖通知 Hook 或 Activity 生命周期，只要微信进程在就能接单
        startAnswerWatch();
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
    /** 当前微信来电通知的 id/tag，用于识别通知被取消（挂断/对方取消） */
    private static volatile int sCallNotifId = Integer.MIN_VALUE;
    private static volatile long sLastOverlayNotify;
    private static volatile String sCallNotifTag;
    private static volatile String sCallName = "";
    private static Handler sWatchHandler;
    private static Runnable sWatchTask;
    /** 最近一次通话 Activity / Intent，最小化后接听命令靠它把界面拉回前台 */
    private static volatile WeakReference<Activity> sCallActivity =
        new WeakReference<>(null);
    private static volatile Intent sCallIntent;
    private static volatile android.app.PendingIntent sCallPendingIntent;
    /** 通知 contentIntent 的抓取时间，通知被清掉后在有效期内仍可打开通话页 */
    private static volatile long sCallPendingAt;
    /** 抓取 contentIntent 时的联系人，避免把上一通的通知用到新通话 */
    private static volatile String sCallPendingName = "";
    /** 微信来电/通话中通知上的操作按钮，悬浮通知和最小化时直接用它接听/挂断 */
    private static volatile Notification.Action[] sCallActions = new Notification.Action[0];
    private static volatile boolean sPendingAnswer;
    /** 待接听命令属于哪一通电话，换来电时必须丢弃旧命令 */
    private static volatile String sPendingAnswerId = "";
    /** 当前命令租约 ID；执行结束（成功或失败）后 ACK，避免微信进程重启丢命令 */
    private static volatile String sPendingCommandId = "";
    /** 最近一次执行动作时看到的界面形态，随状态上报给模块进程做诊断 */
    private static volatile String sLastSurface = "";
    /** 待执行的拒接/挂断命令，等通话页回到前台并完成布局后点击 */
    private static volatile String sPendingEnd = "";
    /** 刚点过悬浮横幅拒接的时间点，用于服务停掉时立即确认拒接成功 */
    private static volatile long sOverlayRejectAt;
    private static Handler sAnswerHandler;
    /** 常驻命令轮询只启动一次，避免每次 onResume 都清掉正在重试的接听/挂断任务 */
    private static volatile boolean sAnswerWatchRunning;
    /** 最近一次看到通话/命令的时间；空闲超过阈值就停掉 700ms 轮询 */
    private static volatile long sAnswerWatchActiveAt;
    private static final long ANSWER_WATCH_IDLE_MS = 20_000L;

    /** 最近一次通话通知的 contentIntent，通知被用户清掉后仍可用于打开通话页 */
    public static android.app.PendingIntent cachedCallPendingIntent() {
        var pending = sCallPendingIntent;
        if (pending == null || sCallPendingAt <= 0) {
            return null;
        }
        return pending;
    }

    public static long cachedCallPendingAt() {
        return sCallPendingAt;
    }

    public static String cachedCallName() {
        return sCallPendingName;
    }

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
        sCallActivity = new WeakReference<>(activity);
        if ("onCreate".equals(where) || "onNewIntent".equals(where)) {
            try {
                sCallIntent = new Intent(activity.getIntent());
            } catch (Throwable ignored) {
                // 忽略
            }
            logIntent(activity, where);
        }
        if ("onCreate".equals(where) || "onResume".equals(where)) {
            scanActiveNotifications(activity);
            dumpVoipMgr(activity.getClassLoader());
        }
        IncomingCallMonitor.onResume(activity);
        if ("onResume".equals(where) && sPendingAnswer) {
            // 页面刚恢复时可能还没完成布局，延迟一点再点接听键
            if (!sPendingAnswerId.isEmpty()
                && !sPendingAnswerId.equals(IncomingCallMonitor.getCallId())) {
                sPendingAnswer = false;
                sPendingAnswerId = "";
                sPendingCommandId = "";
            } else {
                schedulePendingAnswer(activity, 0);
            }
        }
        if ("onResume".equals(where) && !sPendingEnd.isEmpty()) {
            schedulePendingEnd(activity, sPendingEnd, 0);
        }
    }

    private static void schedulePendingEnd(Activity activity, String action, int attempt) {
        sPendingEnd = "";
        var app = AndroidAppHelper.currentApplication();
        var id = IncomingCallMonitor.getCallId();
        if (app == null || id.isEmpty()) {
            return;
        }
        performEnd(app, id, activity, action, attempt);
    }

    private static void schedulePendingAnswer(Activity activity, int attempt) {
        sPendingAnswer = false;
        var app = AndroidAppHelper.currentApplication();
        var id = sPendingAnswerId.isEmpty()
            ? IncomingCallMonitor.getCallId() : sPendingAnswerId;
        sPendingAnswerId = "";
        if (app == null || id.isEmpty()) {
            return;
        }
        performAnswer(app, id, activity, attempt);
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
    /**
     * 接听后微信会把音频模式切到 MODE_IN_COMMUNICATION，
     * 用它作为“已接听”的可靠信号，立即停止循环播报。
     */
    private static void hookAudioMode() {
        try {
            XposedBridge.hookAllMethods(AudioManager.class, "setMode",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0 || !(param.args[0] instanceof Integer)) {
                            return;
                        }
                        var mode = (Integer) param.args[0];
                        if (mode == AudioManager.MODE_IN_COMMUNICATION
                            && IncomingCallMonitor.hasCall()) {
                            IncomingCallMonitor.noteAnswered(
                                AndroidAppHelper.currentApplication());
                        }
                    }
                });
            log("hooked audio mode");
        } catch (Throwable e) {
            log("audio mode hook skipped: " + e);
        }
    }

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
            // 对方取消或本方挂断后，微信会撤掉来电通知，这比界面销毁更可靠
            XposedBridge.hookAllMethods(NotificationManager.class, "cancel",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isCallNotification(param.args)) {
                            IncomingCallMonitor.noteCallGone(
                                AndroidAppHelper.currentApplication());
                        }
                    }
                });
            XposedBridge.hookAllMethods(NotificationManager.class, "cancelAsPackage",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isCallNotification(param.args)) {
                            IncomingCallMonitor.noteCallGone(
                                AndroidAppHelper.currentApplication());
                        }
                    }
                });
            XposedBridge.hookAllMethods(NotificationManager.class, "cancelAll",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (sCallNotifId != Integer.MIN_VALUE) {
                            sCallNotifId = Integer.MIN_VALUE;
                            sCallNotifTag = null;
                            IncomingCallMonitor.noteCallGone(
                                AndroidAppHelper.currentApplication());
                        }
                    }
                });
            XposedBridge.hookAllMethods(Service.class, "stopForeground",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        log("service stopForeground "
                            + param.thisObject.getClass().getName());
                        // 最小化时微信也会 stopForeground，这里只记录，不当作挂断。
                        // 但悬浮横幅刚点过拒接时没有通话页，服务停掉就是对方已被拒接。
                        var rejectAt = sOverlayRejectAt;
                        if (rejectAt > 0
                            && System.currentTimeMillis() - rejectAt < 3_000L
                            && !isCallActivityUsable(sCallActivity.get())) {
                            sOverlayRejectAt = 0L;
                            sPendingEnd = "";
                            reportAndAck(
                                AndroidAppHelper.currentApplication(),
                                IncomingCallMonitor.getCallId(),
                                TriggerContract.VS_REJECTED, "reject confirmed");
                        }
                    }
                });
            for (var method : new String[]{"stopSelf", "stopSelfResult"}) {
                XposedBridge.hookAllMethods(Service.class, method,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            var cls = param.thisObject.getClass().getName();
                            log("service " + method + " " + cls);
                            if (sCallNotifId == Integer.MIN_VALUE) {
                                return;
                            }
                            var lower = cls.toLowerCase();
                            if (lower.contains("voip") || lower.contains("call")) {
                                sCallNotifId = Integer.MIN_VALUE;
                                sCallNotifTag = null;
                                IncomingCallMonitor.noteCallGone(
                                    AndroidAppHelper.currentApplication());
                            }
                        }
                    });
            }
            log("hooked wechat notifications");
        } catch (Throwable e) {
            log("notification hook skipped: " + e);
        }
    }

    private static void hookOverlayWindows() {
        try {
            var wmi = Class.forName("android.view.WindowManagerImpl");
            XposedBridge.hookAllMethods(wmi, "addView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof View) {
                        inspectOverlay((View) param.args[0], "add");
                    }
                }
            });
            XposedBridge.hookAllMethods(wmi, "updateViewLayout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof View) {
                        inspectOverlay((View) param.args[0], "update");
                    }
                }
            });
            log("hooked overlay windows");
        } catch (Throwable e) {
            log("overlay window hook skipped: " + e);
        }
    }

    /** 悬浮来电横幅 / 右上角小窗都没有 Activity，只能从窗口里读文字识别 */
    private static void inspectOverlay(View root, String where) {
        if (root == null) {
            return;
        }
        if (sAnswerHandler == null) {
            sAnswerHandler = new Handler(Looper.getMainLooper());
        }
        sAnswerHandler.postDelayed(() -> {
            try {
                if (!root.isShown()) {
                    return;
                }
                var texts = new java.util.ArrayList<String>();
                collectOverlayTexts(root, texts, 0);
                if (texts.isEmpty()) {
                    return;
                }
                var all = String.join(" ", texts);
                var ringing = all.contains("邀请")
                    || all.contains("等待接听") || all.contains("来电");
                if (!ringing) {
                    // 只认“邀请/等待接听”这类来电横幅，不要把聊天里的“通话中”当来电
                    return;
                }
                var now = System.currentTimeMillis();
                if (now - sLastOverlayNotify < 1_500L) {
                    return;
                }
                sLastOverlayNotify = now;
                var name = "";
                for (var text : texts) {
                    if (text.isEmpty() || text.contains("邀请") || text.contains("通话")
                        || text.contains("接听") || text.contains("拒绝")
                        || text.contains("等待") || text.contains("微信")) {
                        continue;
                    }
                    if (text.length() <= 20) {
                        name = text;
                        break;
                    }
                }
                var video = all.contains("视频");
                log("voip overlay " + where + " ringing=" + ringing + " video=" + video
                    + " name=" + name + " :: " + all);
                IncomingCallMonitor.noteOverlayCall(
                    AndroidAppHelper.currentApplication(), name, video, true);
            } catch (Throwable e) {
                log("overlay inspect failed: " + e);
            }
        }, 300L);
    }

    private static void collectOverlayTexts(View view, java.util.List<String> out, int depth) {
        if (view == null || depth > 12 || !view.isShown()) {
            return;
        }
        if (view instanceof TextView) {
            var text = ((TextView) view).getText();
            if (text != null) {
                var value = text.toString().trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        }
        var desc = view.getContentDescription();
        if (desc != null) {
            var value = desc.toString().trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        if (view instanceof ViewGroup) {
            var group = (ViewGroup) view;
            for (var i = 0; i < group.getChildCount(); i++) {
                collectOverlayTexts(group.getChildAt(i), out, depth + 1);
            }
        }
    }

    private static void handleNotificationArgs(Object[] args) {
        try {
            Notification notification = null;
            var id = Integer.MIN_VALUE;
            String tag = null;
            for (var arg : args) {
                if (arg instanceof Notification) {
                    notification = (Notification) arg;
                } else if (arg instanceof Integer) {
                    id = (Integer) arg;
                } else if (arg instanceof String && tag == null) {
                    tag = (String) arg;
                }
            }
            if (notification != null && handleNotification(notification, true)) {
                sCallNotifId = id;
                sCallNotifTag = tag;
                startNotificationWatch();
            }
        } catch (Throwable e) {
            log("notification parse failed: " + e);
        }
    }

    private static boolean sameCallNotification(StatusBarNotification sbn) {
        try {
            var notification = sbn.getNotification();
            var channel = notification.getChannelId();
            if (channel != null && (channel.contains("voip") || channel.contains("VoIP"))) {
                return true;
            }
            if ("reminder_channel_id".equals(channel) && !sCallName.isEmpty()) {
                var extras = notification.extras;
                if (extras != null) {
                    var all = textOf(extras.getCharSequence(Notification.EXTRA_TITLE))
                        + " " + textOf(extras.getCharSequence(Notification.EXTRA_TEXT));
                    if (all.contains(sCallName)) {
                        return true;
                    }
                }
            }
            return sbn.getId() == sCallNotifId
                && (sCallNotifTag == null || sCallNotifTag.equals(sbn.getTag()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 轮询来电通知是否还在：微信挂断/取消后不一定会调 cancel，
     * 通知从通知栏消失是最可靠的结束信号。
     */
    private static void startNotificationWatch() {
        if (sWatchHandler == null) {
            sWatchHandler = new Handler(Looper.getMainLooper());
        }
        if (sWatchTask != null) {
            sWatchHandler.removeCallbacks(sWatchTask);
        }
        sWatchTask = new Runnable() {
            private int attempts;
            private int absent;
            private boolean seen;
            private int keepAlive;

            @Override
            public void run() {
                if (sCallNotifId == Integer.MIN_VALUE) {
                    return;
                }
                try {
                    var app = AndroidAppHelper.currentApplication();
                    if (app != null) {
                        var manager = (NotificationManager) app
                            .getSystemService(Context.NOTIFICATION_SERVICE);
                        var found = false;
                        var active = manager.getActiveNotifications();
                        for (var sbn : active) {
                            if (sameCallNotification(sbn)) {
                                found = true;
                                break;
                            }
                        }
                        if (found) {
                            seen = true;
                            absent = 0;
                            IncomingCallMonitor.setCallAlive(true);
                            if (++keepAlive % 5 == 0) {
                                // 每 10 秒给播报续命一次，响铃多久就循环多久
                                IncomingCallMonitor.noteCallAlive(app);
                            }
                        } else if (seen) {
                            // 连续 3 次（约 6 秒）都查不到才算真的消失，避免瞬时误判
                            if (++absent >= 3) {
                                LogHelp.i(TAG, "voip notification disappeared, stop");
                                sCallNotifId = Integer.MIN_VALUE;
                                sCallNotifTag = null;
                                IncomingCallMonitor.noteCallGone(app);
                                return;
                            }
                        } else {
                            LogHelp.i(TAG, "notification watch active="
                                + active.length + " want=" + sCallNotifId);
                        }
                    }
                } catch (Throwable e) {
                    log("notification watch failed: " + e);
                }
                if (++attempts < 60) {
                    sWatchHandler.postDelayed(this, 2_000L);
                }
            }
        };
        sWatchHandler.postDelayed(sWatchTask, 2_000L);
        startAnswerWatch();
    }

    /**
     * 最小化/不在通话页时，Session 已经结束，没人轮询接听命令。
     * 这里独立轮询，收到 answer 就把通话页拉回前台，在 onResume 里点绿色接听键。
     */
    /**
     * 供来电监听在 Session 建立时调用，确保即使没有通知 Hook 也有后台接听轮询
     */
    public static void ensureAnswerWatch() {
        startAnswerWatch();
    }

    private static void startAnswerWatch() {
        if (sAnswerWatchRunning) {
            return;
        }
        if (sAnswerHandler == null) {
            sAnswerHandler = new Handler(Looper.getMainLooper());
        }
        if (sAnswerHandler.getLooper() == null) {
            return;
        }
        sAnswerWatchRunning = true;
        sAnswerWatchActiveAt = System.currentTimeMillis();
        sAnswerHandler.post(new Runnable() {
            private String lastId = "";
            private long lastScan;

            @Override
            public void run() {
                var activity = sCallActivity.get();
                var app = AndroidAppHelper.currentApplication();
                var id = IncomingCallMonitor.getCallId();
                if (app == null || id.isEmpty()) {
                    lastId = "";
                    // 悬浮窗/最小化来电没有页面可扫描，通知 Hook 也可能晚于通知发出：
                    // 空闲时定期补读活动通知，避免接口一直 BUSY
                    var now = System.currentTimeMillis();
                    if (now - sAnswerWatchActiveAt > ANSWER_WATCH_IDLE_MS) {
                        sAnswerWatchRunning = false;
                        sPendingCommandId = "";
                        sPendingAnswer = false;
                        sPendingEnd = "";
                        log("voip answer watch idle stop");
                        return;
                    }
                    if (app != null && now - lastScan > 2_000L) {
                        lastScan = now;
                        scanActiveNotifications(app, true);
                    }
                    sAnswerHandler.postDelayed(this, 700L);
                    return;
                }
                sAnswerWatchActiveAt = System.currentTimeMillis();
                if (!id.equals(lastId)) {
                    lastId = id;
                    log("voip answer watch start id=" + id);
                }
                refreshSurface(app, id, activity);
                if (!sPendingCommandId.isEmpty()) {
                    // 上一条命令还在执行/重试中，不要再领新命令，避免重复点击
                    sAnswerHandler.postDelayed(this, 700L);
                    return;
                }
                var polled = VoipReporter.pollCommand(app, id);
                var command = polled.command;
                if (TriggerContract.COMMAND_ANSWER.equals(command)) {
                    log("voip background answer command id=" + id + " attempt=0");
                    sPendingCommandId = polled.commandId;
                    performAnswer(app, id, activity, 0);
                    sAnswerHandler.postDelayed(this, 1_200L);
                    return;
                }
                if (TriggerContract.COMMAND_REJECT.equals(command)
                    || TriggerContract.COMMAND_HANGUP.equals(command)) {
                    log("voip background " + command + " command id=" + id + " attempt=0");
                    sPendingCommandId = polled.commandId;
                    performEnd(app, id, activity, command, 0);
                    sAnswerHandler.postDelayed(this, 1_200L);
                    return;
                }
                sAnswerHandler.postDelayed(this, 700L);
            }
        });
    }

    /**
     * 统一执行接听：优先页面点击；页面不在前台时优先通知按钮；
     * 点完必须等音频模式真正切到通话，避免“接口成功但没接起来”。
     */
    private static void performAnswer(Context app, String id, Activity activity, int attempt) {
        var currentId = IncomingCallMonitor.getCallId();
        if (!currentId.isEmpty() && !currentId.equals(id)) {
            // 上一通的重试链不能落到新来的一通电话上
            log("voip answer drop stale id=" + id
                + " current=" + currentId + " attempt=" + attempt);
            sPendingAnswer = false;
            sPendingAnswerId = "";
            sPendingCommandId = "";
            return;
        }
        if (IncomingCallMonitor.isAnswered()) {
            reportAndAck(app, id, TriggerContract.VS_ANSWERED,
                "answer confirmed by audio mode");
            return;
        }
        var usable = isCallActivityUsable(activity);
        var focused = usable && activity.hasWindowFocus();
        var surface = IncomingCallUi.surfaceName(activity);
        sLastSurface = surface;
        var sent = false;
        log("voip answer surface=" + surface + " focused=" + focused
            + " attempt=" + attempt);
        if (attempt >= 4) {
            reportAndAck(app, id,
                TriggerContract.VS_ANSWER_FAILED, "answer retry limit");
            return;
        }
        if (focused) {
            sent = IncomingCallUi.accept(activity);
            log("voip answer direct focused=" + sent + " attempt=" + attempt);
        }
        if (!sent && IncomingCallUi.SURFACE_BANNER.equals(surface)) {
            // 横幅自己的绿色按钮，按横幅几何换算，不再猜整屏坐标
            sent = IncomingCallUi.acceptOverlay();
            log("voip answer banner=" + sent + " attempt=" + attempt);
        } else if (!sent && IncomingCallUi.SURFACE_MINI.equals(surface)
            && attempt <= 1) {
            // 右上角小窗没有接听键，先点小窗恢复全屏，再在下一轮点绿色按钮
            if (IncomingCallUi.expandMiniWindow()) {
                log("voip answer expand mini attempt=" + attempt);
                sPendingAnswer = true;
                sPendingAnswerId = id;
                sAnswerHandler.postDelayed(() -> {
                    if (!sPendingAnswer) {
                        return;
                    }
                    sPendingAnswer = false;
                    performAnswer(app, id, sCallActivity.get(), attempt + 1);
                }, 1_200L);
                return;
            }
        } else if (!sent && IncomingCallUi.SURFACE_NONE.equals(surface)
            && attempt == 0) {
            // 系统 heads-up 通知：直接触发通知上的“接听”按钮，最稳
            sent = sendCallNotificationAction(true);
            log("voip answer notification=" + sent + " attempt=" + attempt);
        }
        if (!sent && usable) {
            sent = IncomingCallUi.accept(activity);
            log("voip answer direct background=" + sent + " attempt=" + attempt);
        }
        if (!sent && attempt == 0
            && !IncomingCallUi.SURFACE_NONE.equals(surface)) {
            sent = sendCallNotificationAction(true);
            log("voip answer notification fallback=" + sent
                + " attempt=" + attempt);
        }
        if (!sent) {
            if (attempt <= 1) {
                sPendingAnswer = true;
                sPendingAnswerId = id;
                if (bringCallToFront(app)) {
                    log("voip answer bring page attempt=" + attempt);
                    sAnswerHandler.postDelayed(() -> {
                        if (!sPendingAnswer) {
                            return;
                        }
                        sPendingAnswer = false;
                        if (IncomingCallMonitor.isAnswered()) {
                            reportAndAck(app, id, TriggerContract.VS_ANSWERED,
                                "answer confirmed by audio mode");
                            return;
                        }
                        performAnswer(app, id, sCallActivity.get(), attempt + 1);
                    }, 1_200L);
                    return;
                }
                sPendingAnswer = false;
                sPendingAnswerId = "";
            }
            reportAndAck(app, id,
                TriggerContract.VS_ANSWER_FAILED, "call page unavailable");
            return;
        }
        sAnswerHandler.postDelayed(() -> {
            if (IncomingCallMonitor.isAnswered()) {
                reportAndAck(app, id, TriggerContract.VS_ANSWERED,
                    "answer confirmed by audio mode");
                return;
            }
            if (attempt < 3) {
                performAnswer(app, id, activity, attempt + 1);
            } else {
                reportAndAck(app, id,
                    TriggerContract.VS_ANSWER_FAILED, "answer not confirmed");
            }
        }, 1_800L);
    }

    /**
     * 统一执行拒接/挂断：页面在前台直接点；悬浮通知/最小化优先用通知按钮；
     * 点完用通知是否还在确认通话真的结束，不能只看触摸事件是否派发成功。
     */
    private static void performEnd(Context app, String id, Activity activity,
                                   String command, int attempt) {
        var currentId = IncomingCallMonitor.getCallId();
        if (!currentId.isEmpty() && !currentId.equals(id)) {
            // 旧通话的命令/补点链一旦落到新来电，会把下一通直接拒掉
            log("voip " + command + " drop stale id=" + id
                + " current=" + currentId + " attempt=" + attempt);
            sPendingEnd = "";
            sPendingCommandId = "";
            return;
        }
        var reject = TriggerContract.COMMAND_REJECT.equals(command);
        var usable = isCallActivityUsable(activity);
        var focused = usable && activity.hasWindowFocus();
        var surface = IncomingCallUi.surfaceName(activity);
        sLastSurface = surface;
        var sent = false;
        var usedActivity = false;
        log("voip " + command + " surface=" + surface + " focused=" + focused
            + " attempt=" + attempt);
        if (attempt >= 4) {
            // 重试必须有上限，否则横幅已经消失后 bringCallToFront 会无限递归
            reportAndAck(app, id,
                TriggerContract.VS_ANSWER_FAILED,
                (reject ? "reject" : "hangup") + " retry limit");
            return;
        }
        if (focused) {
            sent = reject ? IncomingCallUi.reject(activity) : IncomingCallUi.hangup(activity);
            usedActivity = sent;
            log("voip " + command + " direct focused=" + sent + " attempt=" + attempt);
        }
        if (!sent && !reject && attempt == 0) {
            // 已接通时的悬浮横幅/小窗，优先用通知上的“挂断”动作，
            // 避免不同状态下红色按钮位置变化导致点空。
            sent = sendCallNotificationAction(false);
            log("voip " + command + " notification first=" + sent
                + " attempt=" + attempt);
        }
        if (!sent && IncomingCallUi.SURFACE_BANNER.equals(surface) && attempt <= 1) {
            // 横幅红色按钮：响铃时是左侧拒接，已接通时是右侧挂断
            sent = reject ? IncomingCallUi.rejectOverlay()
                : IncomingCallUi.hangupOverlay();
            if (sent && reject) {
                sOverlayRejectAt = System.currentTimeMillis();
            }
            log("voip " + command + " banner=" + sent + " attempt=" + attempt);
        } else if (!sent && IncomingCallUi.SURFACE_MINI.equals(surface)
            && attempt <= 1) {
            // 右上角小窗没有红色按钮，先展开成全屏页，再点红色按钮
            if (IncomingCallUi.expandMiniWindow()) {
                log("voip " + command + " expand mini attempt=" + attempt);
                sPendingEnd = command;
                sAnswerHandler.postDelayed(() -> {
                    if (sPendingEnd.isEmpty()) {
                        return;
                    }
                    sPendingEnd = "";
                    performEnd(app, id, sCallActivity.get(), command, attempt + 1);
                }, 1_200L);
                return;
            }
        }
        if (!sent && IncomingCallUi.SURFACE_NONE.equals(surface)
            && attempt == 0 && reject) {
            // 系统悬浮通知：直接触发通知上的“拒绝/挂断”按钮
            sent = sendCallNotificationAction(false);
            log("voip " + command + " notification=" + sent + " attempt=" + attempt);
        }
        if (!sent && usable) {
            sent = reject ? IncomingCallUi.reject(activity)
                : IncomingCallUi.hangup(activity);
            usedActivity = sent;
            log("voip " + command + " direct background=" + sent + " attempt=" + attempt);
        }
        if (!sent && attempt == 0) {
            sent = sendCallNotificationAction(false);
            log("voip " + command + " notification fallback=" + sent
                + " attempt=" + attempt);
        }
        if (!sent) {
            if (attempt <= 1) {
                sPendingEnd = command;
                if (bringCallToFront(app)) {
                    log("voip " + command + " bring page attempt=" + attempt);
                    sAnswerHandler.postDelayed(() -> {
                        if (sPendingEnd.isEmpty()) {
                            return;
                        }
                        sPendingEnd = "";
                        performEnd(app, id, sCallActivity.get(), command, attempt + 1);
                    }, 1_200L);
                    return;
                }
                sPendingEnd = "";
            }
            reportAndAck(app, id,
                TriggerContract.VS_ANSWER_FAILED, "call page unavailable");
            return;
        }
        var clickedPage = usedActivity;
        var clickedBanner = IncomingCallUi.SURFACE_BANNER.equals(surface);
        sAnswerHandler.postDelayed(() -> {
            var pageGone = clickedPage && activity != null
                && (activity.isDestroyed() || activity.isFinishing());
            if (clickedBanner && IncomingCallUi.SURFACE_NONE.equals(
                IncomingCallUi.surfaceName(activity))) {
                // 横幅已经消失，说明这次拒接/挂断确实生效；不再继续补点
                reportAndAck(app, id,
                    reject ? TriggerContract.VS_REJECTED : TriggerContract.VS_ENDED,
                    reject ? "banner reject confirmed" : "banner hangup confirmed");
                return;
            }
            // 这里不能读模块 App 的 VoipStore；用本进程会话状态判断是否已结束。
            if (pageGone || !IncomingCallMonitor.isCallAlive()
                || IncomingCallMonitor.getCallId().isEmpty()
                || !id.equals(IncomingCallMonitor.getCallId())) {
                reportAndAck(app, id,
                    reject ? TriggerContract.VS_REJECTED : TriggerContract.VS_ENDED,
                    reject ? "reject confirmed" : "hangup confirmed");
                return;
            }
            if (attempt < 3) {
                performEnd(app, id, activity, command, attempt + 1);
            } else {
                reportAndAck(app, id,
                    TriggerContract.VS_ANSWER_FAILED,
                    (reject ? "reject" : "hangup") + " not confirmed");
            }
        }, reject ? 1_600L : 2_400L);
    }

    /** 形态变化时主动上报，用户手动接听/最小化也能在诊断页看到 full/banner/mini */
    private static void refreshSurface(Context app, String id, Activity activity) {
        var surface = IncomingCallUi.surfaceName(activity);
        if (surface.isEmpty() || IncomingCallUi.SURFACE_NONE.equals(surface)
            || surface.equals(sLastSurface)) {
            return;
        }
        sLastSurface = surface;
        log("voip surface=" + surface + " id=" + id);
        VoipReporter.reportSurface(app, id, surface);
    }

    /**
     * 统一上报终态并 ACK。ACK 必须带 command_id，且只清理匹配的 call_id；
     * 执行失败也 ACK，否则同一命令会在租约到期后被无限重领。
     */
    private static void reportAndAck(Context app, String id, String state, String detail) {
        VoipReporter.reportState(app, id, state, detail);
        if (!sLastSurface.isEmpty()) {
            VoipReporter.reportSurface(app, id, sLastSurface);
        }
        var commandId = sPendingCommandId;
        if (!commandId.isEmpty()) {
            VoipReporter.ackCommand(app, id, commandId);
            sPendingCommandId = "";
        }
    }

    private static boolean isCallActivityUsable(Activity activity) {
        return activity != null && !activity.isDestroyed() && !activity.isFinishing();
    }

    /** 发送微信来电/通话中通知上的“接听”或“挂断”按钮 */
    private static boolean sendCallNotificationAction(boolean answer) {
        for (var action : sCallActions) {
            if (action == null || action.actionIntent == null) {
                continue;
            }
            var title = String.valueOf(action.title).trim();
            var lower = title.toLowerCase();
            var match = answer
                ? (title.contains("接听") || lower.contains("answer")
                    || lower.contains("accept"))
                : (title.contains("挂断") || title.contains("拒绝")
                    || lower.contains("hang") || lower.contains("decline")
                    || lower.contains("end"));
            if (!match) {
                continue;
            }
            try {
                action.actionIntent.send();
                log("voip notification action sent: " + title);
                return true;
            } catch (Throwable e) {
                log("voip notification action failed: " + title + " " + e);
            }
        }
        return false;
    }

    private static boolean bringCallToFront(Context app) {
        try {
            var activity = sCallActivity.get();
            var intent = sCallIntent;
            if (activity != null && !activity.isDestroyed() && !activity.isFinishing()) {
                var resume = new Intent(activity.getIntent() == null
                    ? intent : activity.getIntent());
                resume.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activity.startActivity(resume);
                log("voip bring call activity to front");
                return true;
            }
            if (intent != null) {
                var resume = new Intent(intent);
                resume.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                app.startActivity(resume);
                log("voip relaunch call activity");
                return true;
            }
            var pending = sCallPendingIntent;
            if (pending != null) {
                pending.send();
                log("voip open call page by notification");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!sPendingAnswer && sPendingEnd.isEmpty()) {
                        return;
                    }
                    // 通知入口可能只打开了聊天页，1.5 秒还没回到通话页就直接拉起
                    launchDirectCallPage(app);
                }, 1_500L);
                return true;
            }
            launchDirectCallPage(app);
            return true;
        } catch (Throwable e) {
            log("voip bring call front failed: " + e);
        }
        return false;
    }

    private static void launchDirectCallPage(Context app) {
        try {
            var direct = new Intent();
            direct.setClassName("com.tencent.mm", VIDEO_ACTIVITY);
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(direct);
            log("voip open call page directly");
        } catch (Throwable e) {
            log("voip direct call page failed: " + e);
        }
    }

    private static boolean isCallNotification(Object[] args) {
        var id = Integer.MIN_VALUE;
        String tag = null;
        for (var arg : args) {
            if (arg instanceof Integer) {
                id = (Integer) arg;
            } else if (arg instanceof String && tag == null) {
                tag = (String) arg;
            }
        }
        if (sCallNotifId == Integer.MIN_VALUE) {
            return false;
        }
        if (id != sCallNotifId) {
            return false;
        }
        return sCallNotifTag == null || tag == null || sCallNotifTag.equals(tag);
    }

    /** 界面打开时补读当前活动通知，防止通知 Hook 安装晚于通知发出 */
    private static void scanActiveNotifications(Context context) {
        scanActiveNotifications(context, false);
    }

    private static void scanActiveNotifications(Context context, boolean report) {
        try {
            var manager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            for (var sbn : manager.getActiveNotifications()) {
                if (sbn != null) {
                    handleNotification(sbn.getNotification(), report);
                }
            }
        } catch (Throwable e) {
            log("active notifications failed: " + e);
        }
    }

    private static boolean handleNotification(Notification notification, boolean report) {
        var extras = notification.extras;
        if (extras == null) {
            return false;
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
            return false;
        }
        // 非通话频道的「通话中」多为聊天里的系统提示，不能当来电
        if (!voipChannel && !reminderCall && (all.contains("通话中")
            || all.contains("已接通") || all.contains("正在通话"))) {
            return false;
        }
        if (reminderCall && IncomingCallUi.isOutgoingRecent()) {
            return false;
        }
        var name = pickNotificationName(title, bigTitle, text, bigText, sub, info);
        var video = all.contains("视频");
        log("wechat call notification name=" + name + " video=" + video
            + " channel=" + channelId + " :: " + all);
        if (name.isEmpty()) {
            return false;
        }
        if (reminderCall && !IncomingCallMonitor.hasCall()) {
            // 「视频/语音通话中」是通话状态提醒，不能凭空生成一通新来电
            log("ignore connected reminder without active call: " + name);
            return false;
        }
        if (report && !all.contains("邀请")
            && (all.contains("通话中") || all.contains("已接通")
                || all.contains("正在通话"))) {
            // 主动补读活动通知时，通话中的常驻通知也不能当新来电
            log("ignore connected notification on scan: " + name);
            return false;
        }
        sCallName = name;
        sCallPendingIntent = notification.contentIntent;
        sCallPendingAt = System.currentTimeMillis();
        sCallPendingName = name;
        sCallActions = notification.actions == null
            ? new Notification.Action[0] : notification.actions;
        var actionNames = new StringBuilder();
        for (var action : sCallActions) {
            if (action != null) {
                actionNames.append('[').append(action.title).append(']');
            }
        }
        log("call notification actions=" + actionNames
            + " pendingIntent=" + sCallPendingIntent);
        if (IncomingCallMonitor.hasActiveSession()) {
            IncomingCallMonitor.noteNativeName(name, video);
            // 仍然返回 true：让外层用这条通知的 id 跟踪通话是否还在
            return true;
        }
        if (report) {
            IncomingCallMonitor.noteNotificationIncoming(
                AndroidAppHelper.currentApplication(), name, video);
        } else {
            IncomingCallMonitor.noteNativeName(name, video);
        }
        return true;
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



