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

    /** 候选引擎：第一个为系统默认引擎（null），后面是设备上可用的 TTS 引擎包名 */
    private static final java.util.List<String> CANDIDATES = new java.util.ArrayList<>();
    private static int sCandidateIndex;
    private static boolean sCandidatesBuilt;

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
        MAIN.post(() -> {
            // 用户可能刚在系统设置里装好语音引擎，试听时允许重新探测一次
            if (!sReady && !sInitializing && sUnavailable) {
                sUnavailable = false;
                sCandidateIndex = 0;
                sCandidatesBuilt = false;
                CANDIDATES.clear();
            }
            speak(app, "这是一条微信来电播报测试", true);
        });
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
     * 播报文本。TTS 不可用时不会影响通知，试听场景会给出提示。
     */
    private static void speak(Context app, String text, boolean notifyFailure) {
        sPendingText = text;
        if (notifyFailure) {
            sNotifyFailure = true;
        }
        if (sReady && sTts != null) {
            flushPending();
            return;
        }
        if (sUnavailable) {
            reportFailure(app);
            return;
        }
        if (!sInitializing) {
            startNextEngine(app);
        }
    }

    private static void flushPending() {
        var pending = sPendingText;
        if (pending == null || sTts == null) {
            return;
        }
        sPendingText = null;
        try {
            sTts.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "xp-voip");
        } catch (Throwable e) {
            LogHelp.w(TAG, "tts speak failed: " + e.getMessage());
        }
    }

    /**
     * 依次尝试系统默认引擎和设备上其它 TTS 引擎，避免默认引擎损坏时完全不可用
     */
    private static void startNextEngine(Context app) {
        sApp = app.getApplicationContext();
        buildCandidates(sApp);
        while (sCandidateIndex < CANDIDATES.size()) {
            var engine = CANDIDATES.get(sCandidateIndex++);
            try {
                sInitializing = true;
                if (engine == null) {
                    sTts = new TextToSpeech(sApp, status -> onEngineInit(sApp, status));
                } else {
                    sTts = new TextToSpeech(sApp, status -> onEngineInit(sApp, status), engine);
                }
                return;
            } catch (Throwable e) {
                sInitializing = false;
                sTts = null;
                LogHelp.w(TAG, "tts create failed, engine=" + engine + ": " + e.getMessage());
            }
        }
        sInitializing = false;
        failTts("no tts engine available");
    }

    private static void onEngineInit(Context app, int status) {
        sInitializing = false;
        if (status != TextToSpeech.SUCCESS || sTts == null) {
            LogHelp.w(TAG, "tts init failed, status=" + status
                + ", try next engine");
            closeTts();
            startNextEngine(app);
            return;
        }
        if (!applyChinese(sTts)) {
            LogHelp.w(TAG, "tts chinese unavailable, try next engine");
            closeTts();
            startNextEngine(app);
            return;
        }
        sTts.setSpeechRate(0.95f);
        sReady = true;
        sUnavailable = false;
        sNotifyFailure = false;
        LogHelp.i(TAG, "tts ready");
        flushPending();
    }

    private static boolean applyChinese(TextToSpeech tts) {
        var locales = new Locale[]{Locale.SIMPLIFIED_CHINESE, Locale.CHINA, Locale.CHINESE};
        for (var locale : locales) {
            int result;
            try {
                result = tts.setLanguage(locale);
            } catch (Throwable e) {
                continue;
            }
            if (result != TextToSpeech.LANG_MISSING_DATA
                && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                return true;
            }
        }
        return false;
    }

    private static void buildCandidates(Context app) {
        if (sCandidatesBuilt) {
            return;
        }
        sCandidatesBuilt = true;
        CANDIDATES.add(null);
        try {
            var pm = app.getPackageManager();
            var services = pm.queryIntentServices(
                new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
            for (var info : services) {
                if (info.serviceInfo == null) {
                    continue;
                }
                var pkg = info.serviceInfo.packageName;
                if (pkg != null && !CANDIDATES.contains(pkg)) {
                    CANDIDATES.add(pkg);
                }
            }
        } catch (Throwable e) {
            LogHelp.w(TAG, "query tts engines failed: " + e.getMessage());
        }
        LogHelp.i(TAG, "tts candidates=" + CANDIDATES);
    }

    private static void closeTts() {
        if (sTts == null) {
            return;
        }
        try {
            sTts.stop();
            sTts.shutdown();
        } catch (Throwable ignored) {
            // 忽略
        }
        sTts = null;
    }

    /**
     * 所有引擎都不可用时置位，并给试听用户一个明确提示
     */
    private static void failTts(String reason) {
        sUnavailable = true;
        sReady = false;
        sPendingText = null;
        closeTts();
        LogHelp.w(TAG, "tts unavailable, " + reason);
        reportFailure(sApp);
    }

    private static void reportFailure(Context app) {
        if (!sNotifyFailure || app == null) {
            return;
        }
        sNotifyFailure = false;
        Toast.makeText(app, "系统语音引擎不可用，请在系统设置中安装或启用中文语音引擎",
            Toast.LENGTH_LONG).show();
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
