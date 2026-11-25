package com.cdtubear.xpwechatcall.hook;

import android.app.Activity;
import android.content.Intent;

import com.zgcwkj.comm.LogHelp;
import com.cdtubear.xpwechatcall.voip.IncomingCallMonitor;

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
        // 进程启动/冷启动接电话时，Activity 可能已经 resume 完，补扫一次当前实例
        scanCurrentActivities(loader);
    }

    private interface LifecycleAction {
        void run(Activity activity, XC_MethodHook.MethodHookParam param);
    }

    private static void hookLifecycle(Class<?> clazz, String name,
                                      Class<?>[] parameterTypes,
                                      LifecycleAction action) {
        try {
            var method = XposedHelpers.findMethodExactIfExists(clazz, name, parameterTypes);
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

    private static void dispatch(Activity activity, String where) {
        if (activity == null) {
            return;
        }
        log("voip activity " + where + ": " + activity.getClass().getName());
        if ("onCreate".equals(where) || "onNewIntent".equals(where)) {
            logIntent(activity, where);
        }
        IncomingCallMonitor.onResume(activity);
    }

    /**
     * 打印启动 Intent 的 extra key，便于确认微信用哪个字段区分来/去电
     */
    private static void logIntent(Activity activity, String where) {
        try {
            var intent = activity.getIntent();
            if (intent == null) {
                return;
            }
            var extras = intent.getExtras();
            log(where + " intent action=" + intent.getAction()
                + " extras=" + (extras == null ? "null" : extras.keySet()));
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
