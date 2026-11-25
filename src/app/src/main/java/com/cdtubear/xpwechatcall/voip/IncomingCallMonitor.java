package com.cdtubear.xpwechatcall.voip;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.TriggerContract;
import com.zgcwkj.comm.VoipReporter;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 来电监听
 *
 * 微信来电界面打开后延迟多次扫描界面文字，确认是来电就上报模块进程并开始轮询接听命令；
 * 响铃期间每 700ms 轮询一次，收到 answer 就点击「接听」，同时监听用户手动接听 / 挂断。
 *
 * 只在微信进程内运行，弱引用保存 Activity，避免内存泄漏。
 */
public final class IncomingCallMonitor {

    private static final String TAG = "XpWeChatCall";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "xp-voip-monitor");
        thread.setDaemon(true);
        return thread;
    });

    /** 响铃最长等待时间，超过后按未接处理 */
    private static final long RING_MAX_MS = 75_000L;

    /** 轮询接听命令的间隔 */
    private static final long POLL_MS = 700L;

    /** 手动接听 / 挂断的检测间隔 */
    private static final long WATCH_MS = 1_500L;

    private static final Map<Activity, Session> SESSIONS =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** 微信原生 startVoip 给出的来电信息（Flutter 界面读不到文字时使用） */
    private static volatile String sNativeName = "";
    private static volatile boolean sNativeVideo;
    private static volatile long sNativeAt;
    private static volatile boolean sNativeUsed;

    private IncomingCallMonitor() {
    }

    /**
     * VoipMgr.startVoip 的来电回调：userName / isVideoCall 来自微信原生参数
     */
    public static void noteNativeIncoming(String name, boolean video) {
        sNativeName = name == null ? "" : name.trim();
        sNativeVideo = video;
        sNativeAt = System.currentTimeMillis();
        sNativeUsed = false;
    }

    /** 一次来电会话 */
    private static final class Session {
        final String id;
        final String name;
        final boolean video;
        /** 界面文字是否可读；Flutter 通话界面为 false，不能用界面判断是否接听 */
        final boolean fromText;
        final long startedAt;
        volatile boolean done;
        volatile boolean destroyed;
        volatile boolean accepted;
        volatile boolean reported;

        Session(String id, String name, boolean video, boolean fromText, long startedAt) {
            this.id = id;
            this.name = name;
            this.video = video;
            this.fromText = fromText;
            this.startedAt = startedAt;
        }
    }

    /**
     * 来电 Activity 恢复到前台
     */
    public static void onResume(Activity activity) {
        if (activity == null) {
            return;
        }
        var existing = get(activity);
        LogHelp.i(TAG, "voip activity resume, class=" + activity.getClass().getName()
            + " existing=" + (existing == null ? "none"
                : existing.done ? "done" : "monitoring"));
        if (existing != null && !existing.done) {
            return;
        }
        // 界面文字可能比 onResume 稍晚出现，分几次扫描
        for (var phase = 0; phase < 4; phase++) {
            final var index = phase;
            MAIN.postDelayed(() -> scan(activity, index), new long[]{500, 1200, 2200, 3200}[phase]);
        }
    }

    /**
     * 来电 Activity 销毁：未接听按错过处理，已接听则忽略
     */
    public static void onDestroy(Activity activity) {
        var session = get(activity);
        if (session == null) {
            LogHelp.i(TAG, "voip activity destroyed without session");
            return;
        }
        session.destroyed = true;
        if (!session.reported) {
            var state = session.accepted
                ? TriggerContract.VS_ANSWERED : TriggerContract.VS_MISSED;
            finish(activity, session, state, "call activity destroyed");
        }
        remove(activity);
    }

    /**
     * 延迟扫描一次来电界面
     *
     * @param phase 第几次扫描，最后一次即使没取到名字也开始上报，保证播报不缺席
     */
    private static void scan(Activity activity, int phase) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        var existing = get(activity);
        if (existing != null && !existing.done) {
            return;
        }
        var info = nativePending();
        if (info == null) {
            info = IncomingCallUi.detect(activity);
        }
        if (info == null) {
            if (phase == 3) {
                LogHelp.i(TAG, "voip not ringing, nodes="
                    + IncomingCallUi.describe(activity));
            }
            return;
        }
        if (info.name.isEmpty() && info.fromText && phase < 3) {
            // 名字还没渲染出来，再等下一轮
            return;
        }
        if (info.name.isEmpty() && !info.fromText && phase < 2) {
            // 给原生层 1 秒左右设置联系人，再退化成「微信来电」
            return;
        }
        if (!info.fromText) {
            sNativeUsed = true;
        }
        var session = new Session(newCallId(), info.name, info.video, info.fromText,
            System.currentTimeMillis());
        put(activity, session);
        LogHelp.i(TAG, "voip detected phase=" + phase + " name=" + session.name
            + " video=" + session.video);
        POOL.execute(() -> runSession(activity, session));
        MAIN.postDelayed(() -> watchCall(activity, session), 2000);
    }

    /**
     * 后台线程：上报来电并轮询接听命令
     */
    private static void runSession(Activity activity, Session session) {
        VoipReporter.reportIncoming(activity, session.id, session.name,
            session.video, session.startedAt);
        var deadline = session.startedAt + RING_MAX_MS;
        while (!session.done && !session.destroyed
            && System.currentTimeMillis() < deadline) {
            var command = VoipReporter.pollCommand(activity, session.id);
            if (TriggerContract.COMMAND_ANSWER.equals(command)) {
                var ok = clickAccept(activity);
                if (ok) {
                    session.accepted = true;
                    finish(activity, session, TriggerContract.VS_ANSWERED, "answered by api");
                } else {
                    finish(activity, session, TriggerContract.VS_ANSWER_FAILED,
                        "接听 button not found");
                }
                return;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!session.done && !session.destroyed) {
            finish(activity, session, TriggerContract.VS_MISSED, "ring timeout");
        }
    }

    /**
     * 主线程：判断用户是否已经手动接听 / 挂断 / 对方取消
     */
    private static void watchCall(Activity activity, Session session) {
        if (session.done || session.destroyed) {
            return;
        }
        if (activity.isFinishing() || activity.isDestroyed()) {
            // 交给 onDestroy 处理
            return;
        }
        if (!IncomingCallUi.isRinging(activity)) {
            if (IncomingCallUi.hasEndedText(activity)) {
                finish(activity, session, TriggerContract.VS_MISSED, "caller cancelled");
                return;
            }
            if (!session.fromText) {
                // Flutter 界面没有可读文字，不能把「读不到接听按钮」当成已接听；
                // 继续轮询接听命令，等界面销毁或 75 秒超时再收尾。
                MAIN.postDelayed(() -> watchCall(activity, session), WATCH_MS);
                return;
            }
            session.accepted = true;
            finish(activity, session, TriggerContract.VS_ANSWERED, "answered on device");
            return;
        }
        MAIN.postDelayed(() -> watchCall(activity, session), WATCH_MS);
    }

    /**
     * 在主线程点击接听并等待结果
     */
    private static boolean clickAccept(Activity activity) {
        var ok = new AtomicBoolean();
        var latch = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                ok.set(IncomingCallUi.accept(activity));
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    /**
     * 只上报一次状态，避免轮询线程与监听线程重复回传
     */
    private static void finish(Activity activity, Session session, String state, String detail) {
        synchronized (session) {
            if (session.reported) {
                return;
            }
            session.reported = true;
            session.done = true;
        }
        VoipReporter.reportState(activity, session.id, state, detail);
        LogHelp.i(TAG, "voip finish id=" + session.id + " state=" + state
            + " detail=" + detail);
    }

    /**
     * 原生 startVoip 刚上报、尚未使用的来电信息；超过 20 秒视为过期（避免下一通误用）
     */
    private static IncomingCallUi.Info nativePending() {
        if (sNativeUsed || sNativeAt <= 0
            || System.currentTimeMillis() - sNativeAt > 20_000L) {
            return null;
        }
        return new IncomingCallUi.Info(sNativeName, sNativeVideo, false);
    }

    private static String newCallId() {
        return "voip_" + System.currentTimeMillis() + "_"
            + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
    }

    private static Session get(Activity activity) {
        return SESSIONS.get(activity);
    }

    private static void put(Activity activity, Session session) {
        SESSIONS.put(activity, session);
    }

    private static void remove(Activity activity) {
        SESSIONS.remove(activity);
    }
}
