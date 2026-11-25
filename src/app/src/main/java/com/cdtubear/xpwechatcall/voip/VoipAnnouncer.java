package com.cdtubear.xpwechatcall.voip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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
    /** 完整播报后再重复一遍的短句 */
    private static String sPendingRepeat;
    /** 初始化失败时是否需要提示用户（仅试听场景） */
    private static boolean sNotifyFailure;
    private static Context sApp;

    /** 播报时临时关闭微信铃声，不播报时保持最大音量 */
    private static AudioManager sAudio;
    private static int sOriginalRingVolume = -1;
    private static ToneGenerator sTone;

    /** 来电期间循环播报，直到接听/挂断/超时 */
    private static boolean sLooping;
    private static String sLoopText;
    private static String sLoopRepeat;
    private static Runnable sLoopTask;
    private static Runnable sRoundTimeout;
    /** 兜底：超过这个时间没有新的来电通知，就停止循环，避免挂断后一直播 */
    private static Runnable sLoopDeadline;

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
        var repeat = buildRepeat(video);
        MAIN.post(() -> startLoop(app, text, repeat));
    }

    /**
     * 循环播报：每轮之间把铃声放到最大，播报时再完全关掉
     */
    private static void startLoop(Context app, String text, String repeat) {
        stopLoop();
        sLoopText = text;
        sLoopRepeat = repeat;
        sLooping = true;
        saveAndMaxRing(app);
        scheduleLoopDeadline(app);
        playOneRound(app);
    }

    /** 来电仍在响铃（重复通知/界面轮询）时续命，避免误超时停止 */
    public static void refresh(Context context) {
        if (!sLooping || context == null) {
            return;
        }
        scheduleLoopDeadline(context.getApplicationContext());
    }

    private static void scheduleLoopDeadline(Context app) {
        if (sLoopDeadline != null) {
            MAIN.removeCallbacks(sLoopDeadline);
        }
        sLoopDeadline = () -> {
            if (!sLooping) {
                return;
            }
            LogHelp.i(TAG, "loop deadline reached, stop announcing");
            stopLoop();
            try {
                if (sTts != null) {
                    sTts.stop();
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            restoreOriginalRing();
        };
        MAIN.postDelayed(sLoopDeadline, 40_000L);
    }

    private static void playOneRound(Context app) {
        if (!sLooping) {
            return;
        }
        duckRing();
        beep();
        MAIN.postDelayed(() -> {
            if (sLooping) {
                speak(app, sLoopText, sLoopRepeat, false);
            }
        }, 500);
        if (sRoundTimeout != null) {
            MAIN.removeCallbacks(sRoundTimeout);
        }
        // 兜底：TTS 引擎不回调 onDone 时，18 秒后强制结束本轮，留出铃声间隙
        sRoundTimeout = () -> {
            if (!sLooping) {
                return;
            }
            LogHelp.w(TAG, "tts round timeout, force gap");
            try {
                if (sTts != null) {
                    sTts.stop();
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            restoreMaxRing();
            scheduleNextRound(app, 7_000L);
        };
        MAIN.postDelayed(sRoundTimeout, 18_000L);
    }

    private static void scheduleNextRound(Context app, long delayMs) {
        if (!sLooping) {
            return;
        }
        if (sLoopTask != null) {
            MAIN.removeCallbacks(sLoopTask);
        }
        sLoopTask = () -> {
            if (!sLooping) {
                return;
            }
            sLoopTask = null;
            playOneRound(app);
        };
        MAIN.postDelayed(sLoopTask, delayMs);
    }

    private static void stopLoop() {
        sLooping = false;
        if (sLoopTask != null) {
            MAIN.removeCallbacks(sLoopTask);
            sLoopTask = null;
        }
        if (sRoundTimeout != null) {
            MAIN.removeCallbacks(sRoundTimeout);
            sRoundTimeout = null;
        }
        if (sLoopDeadline != null) {
            MAIN.removeCallbacks(sLoopDeadline);
            sLoopDeadline = null;
        }
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
            speak(app, "这是一条微信来电播报测试", null, true);
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
            stopLoop();
            if (sTts != null) {
                try {
                    sTts.stop();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            restoreOriginalRing();
        });
    }

    private static String buildText(String name, boolean video) {
        var who = name == null ? "" : name.trim();
        var kind = video ? "微信视频电话" : "微信语音电话";
        if (who.isEmpty()) {
            return "请注意，有" + kind + "。按接听键接听，按挂断键挂断。";
        }
        return "请注意，" + who + "打来" + kind
            + "。按接听键接听，按挂断键挂断。";
    }

    private static String buildRepeat(boolean video) {
        return "再说一遍，微信" + (video ? "视频" : "语音")
            + "来电。按接听键接听，按挂断键挂断。";
    }

    /**
     * 播报前先“叮”一声，并临时压低微信铃声，让老人听得清。
     */
    private static void beep() {
        try {
            if (sTone == null) {
                sTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            }
            sTone.startTone(ToneGenerator.TONE_PROP_BEEP2, 500);
        } catch (Throwable e) {
            LogHelp.w(TAG, "tone failed: " + e.getMessage());
        }
    }

    /**
     * 记录用户原铃声，并把来电铃声调到最大
     */
    private static void saveAndMaxRing(Context app) {
        try {
            if (sAudio == null) {
                sAudio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            }
            if (sAudio == null) {
                return;
            }
            if (sOriginalRingVolume < 0) {
                sOriginalRingVolume = sAudio.getStreamVolume(AudioManager.STREAM_RING);
            }
            var max = sAudio.getStreamMaxVolume(AudioManager.STREAM_RING);
            sAudio.setStreamVolume(AudioManager.STREAM_RING, max, 0);
            LogHelp.i(TAG, "ring max " + sOriginalRingVolume + "->" + max);
            // 兜底：最长一通 75 秒，避免异常退出时永久占用铃声
            MAIN.postDelayed(VoipAnnouncer::restoreOriginalRing, 90_000L);
        } catch (Throwable e) {
            LogHelp.w(TAG, "max ring failed: " + e.getMessage());
        }
    }

    private static void duckRing() {
        if (sAudio == null) {
            return;
        }
        try {
            sAudio.setStreamVolume(AudioManager.STREAM_RING, 0, 0);
        } catch (Throwable e) {
            LogHelp.w(TAG, "duck ring failed: " + e.getMessage());
        }
    }

    private static void restoreMaxRing() {
        if (sAudio == null || sOriginalRingVolume < 0) {
            return;
        }
        try {
            sAudio.setStreamVolume(AudioManager.STREAM_RING,
                sAudio.getStreamMaxVolume(AudioManager.STREAM_RING), 0);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void restoreOriginalRing() {
        if (sAudio == null) {
            return;
        }
        try {
            // 用户要求不通话时铃声保持最大，不能恢复成保存时的 0（否则下一通会没铃声）
            var max = sAudio.getStreamMaxVolume(AudioManager.STREAM_RING);
            sAudio.setStreamVolume(AudioManager.STREAM_RING, max, 0);
            LogHelp.i(TAG, "ring kept max " + max);
        } catch (Throwable e) {
            LogHelp.w(TAG, "restore ring failed: " + e.getMessage());
        }
        sOriginalRingVolume = -1;
    }

    /**
     * 播报文本。TTS 不可用时不会影响通知，试听场景会给出提示。
     */
    private static void speak(Context app, String text, String repeat,
                              boolean notifyFailure) {
        sPendingText = text;
        sPendingRepeat = repeat;
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
        var repeat = sPendingRepeat;
        sPendingRepeat = null;
        try {
            var params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM,
                AudioManager.STREAM_MUSIC);
            sTts.speak(pending, TextToSpeech.QUEUE_FLUSH, params, "xp-voip-1");
            if (repeat != null) {
                var params2 = new Bundle();
                params2.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
                params2.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM,
                    AudioManager.STREAM_MUSIC);
                sTts.speak(repeat, TextToSpeech.QUEUE_ADD, params2, "xp-voip-2");
            }
        } catch (Throwable e) {
            LogHelp.w(TAG, "tts speak failed: " + e.getMessage());
            restoreMaxRing();
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
        sTts.setSpeechRate(0.86f);
        sTts.setPitch(1.0f);
        try {
            sTts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
            sTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    MAIN.post(() -> {
                        if (sLooping && "xp-voip-1".equals(utteranceId)) {
                            // 第一句结束，第二句还在播，不能恢复铃声
                            return;
                        }
                        if (sRoundTimeout != null) {
                            MAIN.removeCallbacks(sRoundTimeout);
                            sRoundTimeout = null;
                        }
                        // 部分引擎 onDone 早于最后一段音频真正播完，留 1 秒再恢复铃声
                        MAIN.postDelayed(VoipAnnouncer::restoreMaxRing, 1_500L);
                        if (sLooping && "xp-voip-2".equals(utteranceId)) {
                            scheduleNextRound(sApp, 7_000L);
                        }
                    });
                }

                @Override
                public void onError(String utteranceId) {
                    MAIN.post(() -> {
                        if (sRoundTimeout != null) {
                            MAIN.removeCallbacks(sRoundTimeout);
                            sRoundTimeout = null;
                        }
                        MAIN.postDelayed(VoipAnnouncer::restoreMaxRing, 1_500L);
                        if (sLooping) {
                            scheduleNextRound(sApp, 7_000L);
                        }
                    });
                }
            });
        } catch (Throwable e) {
            LogHelp.w(TAG, "tts audio attrs failed: " + e.getMessage());
        }
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
            channel.setVibrationPattern(new long[]{0, 500, 300, 500, 300, 800});
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
            var body = who.isEmpty() ? "请按屏幕右下角绿色按钮接听"
                : who + " 来电，请按屏幕右下角绿色按钮接听";
            var notification = new Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(who.isEmpty() ? title : title + " · " + who)
                .setContentText(body)
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



