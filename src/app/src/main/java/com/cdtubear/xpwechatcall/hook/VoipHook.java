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
        try {
            var clazz = XposedHelpers.findClass(VIDEO_ACTIVITY, loader);
            XposedHelpers.findAndHookMethod(clazz, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    IncomingCallMonitor.onResume((Activity) param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(clazz, "onNewIntent", Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        IncomingCallMonitor.onResume((Activity) param.thisObject);
                    }
                });
            XposedHelpers.findAndHookMethod(clazz, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    IncomingCallMonitor.onDestroy((Activity) param.thisObject);
                }
            });
            log("hooked " + VIDEO_ACTIVITY);
        } catch (Throwable e) {
            // 微信版本差异或类不存在时跳过来电功能，不影响通话发起
            log("voip hook skipped: " + e);
        }
    }

    private static void log(String message) {
        LogHelp.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
