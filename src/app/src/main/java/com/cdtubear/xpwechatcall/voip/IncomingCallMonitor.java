package com.cdtubear.xpwechatcall.voip;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.cdtubear.xpwechatcall.hook.VoipHook;
import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.TriggerContract;
import com.zgcwkj.comm.VoipReporter;
import android.app.AndroidAppHelper;

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
    private static volatile long sOverlayIdAt;
    private static volatile String sNativeCallId = "";
    /** 这一通电话第一次被识别的时间，call_id 稳定后不再随界面变化 */
    private static volatile long sNativeStartedAt;
    /** 当前通话界面形态：full / banner / mini，仅用于诊断 */
    private static volatile String sSurface = "";
    /** 来电通知是否还在，最小化/锁屏导致 Activity 销毁时用它区分“通话还在” */
    private static volatile boolean sCallAlive;
    /** 这一通是否已经真正接听，挂断时据此上报 ENDED 而不是 MISSED */
    private static volatile boolean sAnswered;

    private IncomingCallMonitor() {
    }

    public static void setCallAlive(boolean alive) {
        sCallAlive = alive;
    }

    public static boolean isCallAlive() {
        return sCallAlive;
    }

    /** 这一通是否已经真正接通（音频模式已切到通话） */
    public static boolean isAnswered() {
        return sAnswered;
    }

    /** 当前是否有一通已识别的来电（用于判断音频模式变化是不是接听） */
    public static boolean hasCall() {
        return !sNativeCallId.isEmpty() || hasActiveSession();
    }

    public static String getCallId() {
        if (!sNativeCallId.isEmpty()) {
            return sNativeCallId;
        }
        synchronized (SESSIONS) {
            for (var session : SESSIONS.values()) {
                if (!session.done && !session.destroyed) {
                    return session.id;
                }
            }
        }
        return "";
    }

    /**
     * 当前这一通电话的识别时间，供微信进程内的通知过滤/兜底判断使用。
     * 不要在这里读 VoipStore：它属于模块 App 进程的私有 prefs。
     */
    public static long getCallStartedAt() {
        if (sNativeStartedAt > 0) {
            return sNativeStartedAt;
        }
        synchronized (SESSIONS) {
            for (var session : SESSIONS.values()) {
                if (!session.done && !session.destroyed) {
                    return session.startedAt;
                }
            }
        }
        return 0L;
    }

    public static String getCallName() {
        if (!sNativeName.isEmpty()) {
            return sNativeName;
        }
        synchronized (SESSIONS) {
            for (var session : SESSIONS.values()) {
                if (!session.done && !session.destroyed) {
                    return session.name;
                }
            }
        }
        return "";
    }

    public static String getSurface() {
        return sSurface;
    }

    public static void setSurface(String surface) {
        sSurface = surface == null ? "" : surface;
    }

    /**
     * 指定 call_id 是否仍然属于当前这一通电话。
     * 用于阻止上一通的 answer/end 重试链在新来电上继续点击。
     */
    public static boolean isCurrent(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (id.equals(sNativeCallId)) {
            return true;
        }
        synchronized (SESSIONS) {
            for (var session : SESSIONS.values()) {
                if (!session.done && !session.destroyed && id.equals(session.id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 当前通话是否处于 AudioManager 通话模式 */
    public static boolean isAudioInCall(android.content.Context context) {
        try {
            var am = (android.media.AudioManager) context
                .getSystemService(android.content.Context.AUDIO_SERVICE);
            return am != null
                && am.getMode() == android.media.AudioManager.MODE_IN_COMMUNICATION;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 悬浮横幅 / 小窗：没有 Activity，也没有可读的来电通知，
     * 从窗口里的文字识别出联系人并建立 call_id，接口才能工作。
     */
    public static void noteOverlayCall(android.content.Context context, String name,
                                       boolean video, boolean ringing) {
        if (context == null) {
            return;
        }
        var clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            clean = "微信通话";
        }
        var now = System.currentTimeMillis();
        // 这里运行在微信进程，不能读模块 App 的 VoipStore；call_id 只由本进程的
        // 会话状态决定。上一通结束时 noteCallGone() 已清空 ID，不会误复用。
        var sameCall = !sNativeCallId.isEmpty() && now - sNativeAt < 45_000L;
        if (ringing && !sNativeCallId.isEmpty()
            && (!clean.equals(sNativeName) || !sameCall)) {
            // 联系人变了或上一通已经超时，按新来电处理
            sNativeCallId = "";
            sAnswered = false;
            sCallAlive = false;
        }
        sNativeName = clean;
        sNativeVideo = video;
        sNativeAt = now;
        sCallAlive = true;
        if (ringing) {
            // 悬浮横幅/小窗没有 Activity.onResume，必须在这里拉起命令轮询，
            // 否则接口虽然入队，但微信进程里没人执行 answer/end。
            VoipHook.ensureAnswerWatch();
        }
        if (sAnswered) {
            if (!sNativeCallId.isEmpty()) {
                VoipReporter.reportState(context, sNativeCallId,
                    TriggerContract.VS_ANSWERED, "overlay call still connected");
            }
            return;
        }
        if (sNativeCallId.isEmpty()) {
            sNativeCallId = "voip_float_" + now + "_"
                + Integer.toHexString(clean.hashCode());
            sNativeStartedAt = now;
            sOverlayIdAt = now;
            sNativeUsed = true;
            LogHelp.i(TAG, "voip overlay incoming name=" + clean
                + " video=" + video + " id=" + sNativeCallId);
            VoipReporter.reportIncoming(context, sNativeCallId, clean, video, now);
        } else if (ringing) {
            // 同一通电话的通知/界面刷新只续命，不换 call_id、不重复播报
            VoipReporter.reportIncoming(context, sNativeCallId, clean, video,
                sNativeStartedAt > 0 ? sNativeStartedAt : now);
        } else {
            VoipReporter.reportState(context, sNativeCallId,
                TriggerContract.VS_ANSWERED, "overlay call connected");
        }
    }

    /**
     * 微信把音频模式切到通话模式，说明已经接听：立即上报 ANSWERED 停止播报，
     * 避免接听后进入 7 秒间隙，挂断时又补播一轮。
     */
    public static void noteAnswered(android.content.Context context) {
        var id = getCallId();
        if (context == null || id.isEmpty()) {
            return;
        }
        LogHelp.i(TAG, "voip answered by audio mode id=" + id);
        sAnswered = true;
        VoipReporter.reportState(context, id,
            TriggerContract.VS_ANSWERED, "audio mode in call");
    }

    /**
     * 微信撤掉来电通知：对方取消、本方挂断、或接听后通知被替换
     * 立即结束播报，避免电话已经没了还在循环。
     */
    public static void noteCallGone(android.content.Context context) {
        var hadCall = sNativeAt > 0 || !sNativeCallId.isEmpty();
        var answered = sAnswered;
        sCallAlive = false;
        sAnswered = false;
        var notifId = sNativeCallId;
        sNativeCallId = "";
        sNativeAt = 0;
        sNativeStartedAt = 0;
        sSurface = "";
        sNativeUsed = true;
        synchronized (SESSIONS) {
            for (var session : SESSIONS.values()) {
                if (session.done || session.destroyed) {
                    continue;
                }
                session.done = true;
                hadCall = true;
                answered = answered || session.accepted;
                if (context != null) {
                    VoipReporter.reportState(context, session.id,
                        answered ? TriggerContract.VS_ENDED
                            : TriggerContract.VS_MISSED,
                        "call notification cancelled");
                }
            }
        }
        if (context != null && !notifId.isEmpty()) {
            VoipReporter.reportState(context, notifId,
                answered ? TriggerContract.VS_ENDED : TriggerContract.VS_MISSED,
                "call notification cancelled");
        }
        if (hadCall) {
            LogHelp.i(TAG, "voip notification gone, stop announcing");
        }
    }

    /**
     * 是否已有正在响铃/通话的会话，避免重复播报
     */
    public static boolean hasActiveSession() {
        synchronized (SESSIONS) {
            for (var session : SESSIONS.values()) {
                if (!session.done && !session.destroyed) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 只补充联系人信息，不在这里触发播报（播报由通知上报或界面会话负责）
     */
    public static void noteNativeName(String name, boolean video) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        sNativeName = name.trim();
        sNativeVideo = video;
        if (sNativeAt == 0) {
            sNativeAt = System.currentTimeMillis();
        }
        LogHelp.i(TAG, "voip native name=" + sNativeName + " video=" + video);
    }

    /**
     * 微信来电通知：即使没有打开全屏通话界面，也能拿到联系人和类型并立即播报
     */
    /**
     * 通话通知还在：给模块进程续命，避免循环播报被超时停掉
     *
     * @param context 微信上下文
     */
    public static void noteCallAlive(android.content.Context context) {
        if (context == null || sNativeCallId.isEmpty() || sNativeName.isEmpty()) {
            return;
        }
        if (sAnswered) {
            // 已接通的视频通话微信仍会周期性刷新通知，不能再用 incoming 把状态打回 RINGING
            VoipReporter.reportState(context, sNativeCallId,
                TriggerContract.VS_ANSWERED, "call still connected");
            return;
        }
        VoipReporter.reportIncoming(context, sNativeCallId, sNativeName,
            sNativeVideo, System.currentTimeMillis());
    }

    public static void noteNotificationIncoming(android.content.Context context,
                                                String name, boolean video) {
        var now = System.currentTimeMillis();
        var cleanName = name == null ? "" : name.trim();
        if (sAnswered && !sNativeCallId.isEmpty()) {
            // 接听后通知内容会从“邀请你通话”变成“通话中”，但 call_id 不变；
            // 这里必须保持 ANSWERED，否则 /v1/end 会误判为没有接通
            sNativeAt = now;
            sNativeVideo = video;
            if (context != null) {
                VoipReporter.reportState(context, sNativeCallId,
                    TriggerContract.VS_ANSWERED, "call still connected");
            }
            return;
        }
        // 响铃期间微信会反复发提醒通知：沿用同一个 call_id，只续命不重复播报
        if (!sNativeCallId.isEmpty() && now - sNativeAt < 45_000L
            && sNativeName.equals(cleanName)) {
            sNativeAt = now;
            sNativeVideo = video;
            if (context != null) {
                VoipReporter.reportIncoming(context, sNativeCallId,
                    cleanName, video, sNativeAt);
            }
            return;
        }
        sNativeName = cleanName;
        sNativeVideo = video;
        sNativeAt = now;
        sNativeUsed = false;
        sAnswered = false;
        sNativeStartedAt = now;
        sNativeCallId = "voip_notif_" + now + "_"
            + Integer.toHexString(sNativeName.hashCode());
        LogHelp.i(TAG, "voip notification incoming name=" + sNativeName
            + " video=" + video + " id=" + sNativeCallId);
        if (context != null) {
            VoipReporter.reportIncoming(context, sNativeCallId,
                sNativeName, video, now);
        }
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
        // 新一通来电：即使通知 Hook 没拿到，也要启动独立接听轮询
        VoipHook.ensureAnswerWatch();
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
            if (!sCallAlive) {
                // 页面销毁可能只是最小化成小窗，不能立刻清 id；
                // 用延迟空闲检查收尾，避免接口变成 BUSY
                scheduleIdleCheck(AndroidAppHelper.currentApplication());
            }
            return;
        }
        session.destroyed = true;
        if (!session.reported) {
            // 通话页销毁不等于挂断：最小化也会销毁。真正的结束由通知撤销 /
            // 服务停止 / 延迟空闲检查判定。
            LogHelp.i(TAG, "voip activity destroyed, wait for call end signal");
        }
        remove(activity);
        if (!sCallAlive) {
            // 小窗状态下仍要保留 id，接口和小窗按钮才能继续工作
            scheduleIdleCheck(AndroidAppHelper.currentApplication());
        }
    }

    /** 通话页消失后延迟判定是否真的结束；小窗/最小化期间提醒通知会持续续命 */
    private static void scheduleIdleCheck(android.content.Context context) {
        var id = getCallId();
        if (context == null || id.isEmpty()) {
            return;
        }
        MAIN.postDelayed(() -> {
            if (!id.equals(getCallId())) {
                return;
            }
            if (hasActiveSession() || isAudioInCall(context)) {
                return;
            }
            if (System.currentTimeMillis() - sNativeAt < 6_000L) {
                // 微信的“通话中”提醒还在刷新，说明通话/小窗还活着
                return;
            }
            LogHelp.i(TAG, "voip idle, treat call as ended id=" + id);
            var answered = sAnswered;
            sCallAlive = false;
            sAnswered = false;
            if (id.equals(sNativeCallId)) {
                sNativeCallId = "";
                sNativeAt = 0;
            }
            VoipReporter.reportState(context, id,
                answered ? TriggerContract.VS_ENDED : TriggerContract.VS_MISSED,
                "call page and notification idle");
        }, 8_000L);
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
        var displayName = info.name;
        if ((displayName.isEmpty() || "最近".equals(displayName)
            || "通话".equals(displayName) || "微信".equals(displayName))
            && !sNativeName.isEmpty()) {
            displayName = sNativeName;
        }
        var sessionId = sNativeCallId.isEmpty() ? newCallId() : sNativeCallId;
        if (sNativeCallId.isEmpty()) {
            // 没有通知也能从通话页拿到 id：后台轮询和接口命令必须用同一个 id
            sNativeCallId = sessionId;
            sNativeName = displayName;
            sNativeVideo = info.video;
            sNativeAt = System.currentTimeMillis();
        }
        sCallAlive = true;
        var session = new Session(sessionId, displayName, info.video, info.fromText,
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
            // 接听/拒接/挂断统一由 VoipHook 的常驻轮询执行并做结果校验，
            // 这里只负责上报来电和等待会话结束，避免两个轮询同时取命令。
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!session.done && !session.destroyed && !sAnswered) {
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
     * 在主线程点击拒接/挂断并等待结果
     */
    private static boolean clickEnd(Activity activity, boolean reject) {
        var ok = new AtomicBoolean();
        var latch = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                ok.set(reject ? IncomingCallUi.reject(activity)
                    : IncomingCallUi.hangup(activity));
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
        if (TriggerContract.VS_ANSWERED.equals(state)) {
            sAnswered = true;
        }
        // 超时/取消/失败后清掉当前来电，否则同一联系人再次打进来会沿用旧 call_id，
        // 被当成重复通知（fresh=false）而不播报、也不启动后台接听轮询
        if (!TriggerContract.VS_ANSWERED.equals(state)
            && session.id.equals(sNativeCallId)) {
            sNativeCallId = "";
            sNativeAt = 0;
            sCallAlive = false;
            sNativeUsed = true;
        }
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

