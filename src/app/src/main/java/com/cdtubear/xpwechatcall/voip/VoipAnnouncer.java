package com.cdtubear.xpwechatcall.voip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import com.cdtubear.xpwechatcall.MainActivity;
import com.cdtubear.xpwechatcall.R;
import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.TriggerContract;

import java.util.Locale;

/**
 * 微信来电播报
 *
 * 用系统 TTS 播报来电人名字，同时发一条高优先级通知（带「接听」按钮）。
 * 运行在模块进程，由微信进程通过 Provider 触发。
 */
public final class VoipAnnouncer {

    private static final String TAG = "XpWeChatCall";
    private static final String CHANNEL = "voip_incoming";
    private static final int NOTIFICATION_ID = 3101;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static TextToSpeech sTts;
    private static boolean sReady;
    private static boolean sUnavailable;
    private static boolean sInitializing;
    /** TTS 初始化期间收到的播报文本，就绪后补播 */
    private static String sPendingText;
    /** 初始化失败时是否需要提示用户（仅试听场景） */
    private static boolean sNotifyFailure;
    private static Context sApp;

    private VoipAnnouncer() {
    }

    /**
     * 播报一通来电
     */
    public static void announce(Context context, String name, String type) {
        if (context == null || !TriggerContract.voipAnnounce(context)) {
            return;
        }
        var app = context.getApplicationContext();
        var video = TriggerContract.CALL_VIDEO.equals(type);
        showNotification(app, name, video);
        var text = buildText(name, video);
        MAIN.post(() -> speak(app, text, false));
    }

    /**
     * 试听播报，设置页用来确认系统 TTS 是否可用
     */
    public static void preview(Context context) {
        var app = context.getApplicationContext();
        MAIN.post(() -> speak(app, "这是一条微信来电播报测试", true));
    }

    /**
     * 接听 / 挂断 / 结束后停止播报并撤掉通知
     */
    public static void stop(Context context) {
        try {
            var manager = (NotificationManager) context.getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        MAIN.post(() -> {
            if (sTts != null) {
                try {
                    sTts.stop();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        });
    }

    private static String buildText(String name, boolean video) {
        var who = name == null ? "" : name.trim();
        var type = video ? "微信视频来电" : "微信语音来电";
        return who.isEmpty() ? type : who + "，" + type;
    }

    /**
     * 播报文本，TTS 不可用时静默失败，通知仍然保留
     */
    private static void speak(Context app, String text, boolean notifyFailure) {
        initTts(app);
        if (sReady && sTts != null) {
            try {
                sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "xp-voip");
                return;
            } catch (Throwable e) {
                LogHelp.w(TAG, "tts speak failed: " + e.getMessage());
            }
        }
        if (!sUnavailable) {
            // 引擎还在初始化，先记下来，就绪后补播
            sPendingText = text;
            if (notifyFailure) {
                sNotifyFailure = true;
            }
        } else if (notifyFailure) {
            Toast.makeText(app, "系统语音引擎不可用，请安装支持中文的 TTS",
                Toast.LENGTH_LONG).show();
        }
    }

    private static void initTts(Context app) {
        if (sReady || sUnavailable || sInitializing) {
            return;
        }
        sInitializing = true;
        sApp = app;
        try {
            sTts = new TextToSpeech(app, status -> {
                sInitializing = false;
                if (status != TextToSpeech.SUCCESS || sTts == null) {
                    failTts("init failed: " + status);
                    return;
                }
                var result = sTts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    result = sTts.setLanguage(Locale.CHINA);
                }
                if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    failTts("chinese unavailable: " + result);
                    return;
                }
                sTts.setSpeechRate(0.95f);
                sReady = true;
                sNotifyFailure = false;
                LogHelp.i(TAG, "tts ready");
                var pending = sPendingText;
                sPendingText = null;
                if (pending != null) {
                    try {
                        sTts.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "xp-voip");
                    } catch (Throwable e) {
                        LogHelp.w(TAG, "tts pending speak failed: " + e.getMessage());
                    }
                }
            });
        } catch (Throwable e) {
            sInitializing = false;
            failTts("create failed: " + e.getMessage());
        }
    }

    /**
     * 标记语音引擎不可用；试听场景下提示用户，避免只看到「正在试听」却没有任何声音
     */
    private static void failTts(String reason) {
        sUnavailable = true;
        sPendingText = null;
        LogHelp.w(TAG, "tts unavailable, " + reason);
        if (sNotifyFailure && sApp != null) {
            sNotifyFailure = false;
            Toast.makeText(sApp, "系统语音引擎不可用，请安装支持中文的 TTS",
                Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 高优先级通知，带「接听」按钮；通知本身不响铃，避免和微信来电铃声重叠
     */
    private static void showNotification(Context app, String name, boolean video) {
        try {
            var manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            var channel = new NotificationChannel(CHANNEL, "微信来电播报",
                NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("微信来电时播报联系人并发通知");
            channel.setSound(null, null);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 300, 200, 300});
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);

            var intent = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_HOME);
            var contentIntent = PendingIntent.getActivity(app, 100, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            var answerIntent = new Intent(app, VoipActionReceiver.class)
                .setAction(VoipActionReceiver.ACTION_ANSWER);
            var answerPending = PendingIntent.getBroadcast(app, 101, answerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            var title = video ? "微信视频来电" : "微信语音来电";
            var who = name == null ? "" : name.trim();
            var notification = new Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(who.isEmpty() ? "未知联系人" : who)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .addAction(new Notification.Action.Builder(
                    Icon.createWithResource(app, R.drawable.ic_launcher), "接听",
                    answerPending).build())
                .build();
            manager.notify(NOTIFICATION_ID, notification);
        } catch (Throwable e) {
            LogHelp.w(TAG, "voip notification failed: " + e.getMessage());
        }
    }
}
