package com.cdtubear.xpwechatcall.voip;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.zgcwkj.comm.LogHelp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 来电界面识别与接听
 *
 * 微信的视频/语音来电界面没有稳定的对外接口，这里沿用发起通话时的思路：
 * 只依赖界面文案（「接听」「挂断」「邀请你视频通话」）和官方点击入口，
 * 不依赖混淆类名与内部字段。
 *
 * 运行在微信进程内，所有方法都必须在主线程调用。
 */
public final class IncomingCallUi {

    private static final String TAG = "XpWeChatCall";

    /** 模块自己发起通话的有效期，期间出现的 VideoActivity 不当成来电 */
    private static volatile long outgoingUntil;

    /** 接听按钮文案 */
    public static final String TEXT_ACCEPT = "接听";

    /** 来电界面上的功能文案，不能当作联系人名字 */
    private static final Set<String> LABELS = new HashSet<>(Arrays.asList(
        "接听", "挂断", "免提", "静音", "摄像头", "翻转", "切换", "切换摄像头",
        "视频通话", "语音通话", "邀请你视频通话", "邀请你语音通话",
        "邀请你进行视频通话", "邀请你进行语音通话",
        "切换到视频通话", "切换到语音通话", "取消", "关闭", "更多", "设置",
        "微信", "通话中", "正在呼叫", "等待对方接受邀请", "连接中", "正在连接",
        "网络不佳", "网络异常", "对方", "未知", "未知联系人"
    ));

    /** 通话已结束类文案，用来区分「已接听」和「对方取消」 */
    private static final String[] ENDED_TEXTS = {
        "已取消", "已结束", "未接听", "通话结束", "对方已挂断", "对方已取消"
    };

    private IncomingCallUi() {
    }

    /** 识别结果 */
    public static final class Info {
        public final String name;
        public final boolean video;
        /** 是否真的从界面读到了文字（Flutter 界面为 false） */
        public final boolean fromText;

        Info(String name, boolean video) {
            this(name, video, true);
        }

        Info(String name, boolean video, boolean fromText) {
            this.name = name;
            this.video = video;
            this.fromText = fromText;
        }
    }

    /**
     * 判断当前界面是否是来电，并取出来电人名字与通话类型
     *
     * 返回 null 表示不是来电界面（例如自己发起的通话）。
     */
    public static Info detect(Activity activity) {
        if (activity == null) {
            return null;
        }
        var nodes = collect(activity);
        // 新版微信的接听按钮只有图标、没有「接听」文字，因此「邀请你视频/语音通话」也算来电
        if (!hasAccept(nodes) && !hasInvite(nodes)) {
            // 兜底：VideoActivity 已在前台、界面文字抓不到（系统窗口可能尚未布局完成），
            // 且不是本模块刚发起的通话，就按来电处理，避免漏播报。
            if (!looksLikeOutgoing(nodes) && !isOutgoingRecent()
                && activity.getWindow() != null
                && activity.getWindow().getDecorView().isShown()) {
                var video = hasVideoChrome(nodes);
                return new Info(pickName(nodes), video, false);
            }
            return null;
        }
        var video = false;
        for (var node : nodes) {
            // 语音来电页也有「切换到视频通话」，不能据此判定为视频来电
            if (node.text.contains("切换")) {
                continue;
            }
            if (node.text.contains("视频通话")) {
                video = true;
                break;
            }
        }
        return new Info(pickName(nodes), video);
    }

    /** 本模块发起通话前调用，避免把去电界面误判成来电 */
    public static void noteOutgoing() {
        outgoingUntil = System.currentTimeMillis() + 90_000L;
    }

    public static boolean isOutgoingRecent() {
        return System.currentTimeMillis() < outgoingUntil;
    }

    /** 去电界面的典型文案 */
    private static boolean looksLikeOutgoing(List<TextNode> nodes) {
        for (var node : nodes) {
            if (node.text.contains("等待对方接受")
                || node.text.contains("正在呼叫")
                || node.text.contains("对方已拒绝")) {
                return true;
            }
        }
        return false;
    }

    /** 视频通话界面特征按钮，用于判断 video=true */
    private static boolean hasVideoChrome(List<TextNode> nodes) {
        var flip = false;
        var blur = false;
        for (var node : nodes) {
            if (node.text.contains("翻转")) {
                flip = true;
            }
            if (node.text.contains("模糊背景") || node.text.contains("摄像头")) {
                blur = true;
            }
        }
        return flip && blur;
    }

    /**
     * 是否仍在响铃（界面上还有「接听」按钮）
     */
    public static boolean isRinging(Activity activity) {
        if (activity == null) {
            return false;
        }
        var nodes = collect(activity);
        return hasAccept(nodes) || hasInvite(nodes);
    }

    /**
     * 是否显示「邀请你视频/语音通话」文案（来电页标题）
     */
    private static boolean hasInvite(List<TextNode> nodes) {
        for (var node : nodes) {
            var text = node.text;
            if (!text.contains("邀请你")) {
                continue;
            }
            if (text.contains("视频通话") || text.contains("语音通话")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 界面是否已经显示通话结束提示
     */
    public static boolean hasEndedText(Activity activity) {
        if (activity == null) {
            return false;
        }
        for (var node : collect(activity)) {
            for (var ended : ENDED_TEXTS) {
                if (node.text.contains(ended)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 点击「接听」
     *
     * 依次尝试按钮自身、可点击祖先、按坐标派发触摸事件，尽量兼容各家 ROM 的控件结构。
     */
    public static boolean accept(Activity activity) {
        if (activity == null) {
            return false;
        }
        var target = findAccept(activity);
        if (target != null) {
            if (clickCompat(target)) {
                LogHelp.i(TAG, "voip accept: click ok, view=" + target.getClass().getName());
                return true;
            }
            if (tapCenter(target)) {
                LogHelp.i(TAG, "voip accept: tap ok");
                return true;
            }
            LogHelp.w(TAG, "voip accept: click failed, view=" + target.getClass().getName());
            return false;
        }
        // 图标式接听键：优先用实测比例坐标，几何查找容易误点到整块底部布局
        if (tapAnswerByRatio(activity)) {
            LogHelp.i(TAG, "voip accept: ratio tap ok");
            return true;
        }
        target = findAnswerByGeometry(activity);
        if (target == null) {
            LogHelp.w(TAG, "voip accept: 接听 button not found");
            return false;
        }
        if (clickCompat(target)) {
            LogHelp.i(TAG, "voip accept: click ok, view=" + target.getClass().getName());
            return true;
        }
        if (tapCenter(target)) {
            LogHelp.i(TAG, "voip accept: tap ok");
            return true;
        }
        LogHelp.w(TAG, "voip accept: click failed, view=" + target.getClass().getName());
        return false;
    }

    /** 拒接正在响铃的来电 */
    public static boolean reject(Activity activity) {
        return endCall(activity, "拒绝", true);
    }

    /** 挂断已经接通的通话 */
    public static boolean hangup(Activity activity) {
        return endCall(activity, "挂断", false);
    }

    private static boolean endCall(Activity activity, String text, boolean reject) {
        if (activity == null) {
            return false;
        }
        var roots = roots(activity);
        for (var root : roots) {
            var view = firstByText(root, text, false);
            if (view != null && clickCompat(view)) {
                LogHelp.i(TAG, "voip " + text + ": click ok");
                return true;
            }
            if (view != null && tapCenter(view)) {
                LogHelp.i(TAG, "voip " + text + ": text tap ok");
                return true;
            }
        }
        // 图标式按钮：来电拒接键在左下角，已接通的挂断键在底部中间
        try {
            var decor = activity.getWindow() == null
                ? null : activity.getWindow().getDecorView();
            if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
                return false;
            }
            var x = decor.getWidth() * (reject ? 0.207f : 0.5f);
            // 响铃页拒接键中心实测 (224,2035)=0.207w/0.870h；
            // 已接通页红色挂断键中心约 0.892h
            var y = decor.getHeight() * (reject ? 0.870f : 0.892f);
            var downTime = SystemClock.uptimeMillis();
            var down = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, x, y, 0);
            var up = MotionEvent.obtain(downTime, downTime + 40,
                MotionEvent.ACTION_UP, x, y, 0);
            decor.dispatchTouchEvent(down);
            decor.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
            LogHelp.i(TAG, "voip " + text + ": ratio tap " + (int) x + "," + (int) y);
            return true;
        } catch (Throwable e) {
            LogHelp.w(TAG, "voip " + text + " failed: " + e);
            return false;
        }
    }

    /**
     * 图标式接听按钮：屏幕右下区域、可点击、尺寸够大的控件
     */
    private static View findAnswerByGeometry(Activity activity) {
        try {
            var decor = activity.getWindow() == null
                ? null : activity.getWindow().getDecorView();
            if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
                return null;
            }
            var density = activity.getResources().getDisplayMetrics().density;
            var minSize = (int) (56 * density);
            var views = new ArrayList<View>();
            for (var root : roots(activity)) {
                collectViews(root, views);
            }
            View best = null;
            var bestY = -1;
            var width = decor.getWidth();
            var height = decor.getHeight();
            for (var view : views) {
                if (!view.isShown() || !view.isClickable()) {
                    continue;
                }
                var w = view.getWidth();
                var h = view.getHeight();
                if (w < minSize || h < minSize || w > width * 0.6f) {
                    continue;
                }
                var loc = new int[2];
                view.getLocationOnScreen(loc);
                var cx = loc[0] + w / 2f;
                var cy = loc[1] + h / 2f;
                if (cx < width * 0.5f || cy < height * 0.55f) {
                    continue;
                }
                var label = labelOf(view);
                if (label.contains("翻转") || label.contains("模糊")
                    || label.contains("摄像头") || label.contains("切换")
                    || label.contains("静音") || label.contains("免提")) {
                    continue;
                }
                if (cy > bestY || (cy == bestY && best != null
                    && w * h > best.getWidth() * best.getHeight())) {
                    best = view;
                    bestY = (int) cy;
                }
            }
            if (best != null) {
                LogHelp.i(TAG, "voip accept: geometry hit " + best.getClass().getName()
                    + " " + best.getWidth() + "x" + best.getHeight());
            }
            return best;
        } catch (Throwable e) {
            LogHelp.w(TAG, "voip accept geometry failed: " + e);
            return null;
        }
    }

    private static String labelOf(View view) {
        var builder = new StringBuilder();
        var desc = view.getContentDescription();
        if (desc != null) {
            builder.append(desc);
        }
        if (view instanceof TextView) {
            var text = ((TextView) view).getText();
            if (text != null) {
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private static void collectViews(View view, List<View> out) {
        if (view == null) {
            return;
        }
        out.add(view);
        if (view instanceof ViewGroup) {
            var group = (ViewGroup) view;
            for (var i = 0; i < group.getChildCount(); i++) {
                collectViews(group.getChildAt(i), out);
            }
        }
    }

    /**
     * 查找「接听」按钮，优先精确匹配，避免点成「接听并打开摄像头」等衍生按钮
     */
    private static View findAccept(Activity activity) {
        var roots = roots(activity);
        for (var root : roots) {
            var view = firstByText(root, TEXT_ACCEPT, true);
            if (view != null) {
                return view;
            }
        }
        for (var root : roots) {
            var view = firstByText(root, TEXT_ACCEPT, false);
            if (view != null) {
                return view;
            }
        }
        for (var root : roots) {
            var view = firstByDesc(root, TEXT_ACCEPT);
            if (view != null) {
                return view;
            }
        }
        return null;
    }

    private static boolean hasAccept(List<TextNode> nodes) {
        for (var node : nodes) {
            if (node.text.startsWith(TEXT_ACCEPT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从来电界面中挑出联系人名字
     *
     * 昵称通常是界面上字号最大的非按钮文字；用字号排序，同字号时取更靠上的，
     * 并过滤状态、时间、网络等噪声。
     */
    private static String pickName(List<TextNode> nodes) {
        var best = "";
        var bestSize = -1f;
        var bestY = Integer.MAX_VALUE;
        for (var node : nodes) {
            var text = node.text.trim();
            if (text.isEmpty() || text.length() > 24) {
                continue;
            }
            if (LABELS.contains(text)) {
                continue;
            }
            var invite = text.indexOf("邀请你");
            if (invite > 0) {
                text = text.substring(0, invite).trim();
            } else if (invite == 0) {
                continue;
            }
            if (text.isEmpty() || text.contains("微信") || text.contains("通话")) {
                continue;
            }
            if (isNoise(text)) {
                continue;
            }
            if (node.size > bestSize || (node.size == bestSize && node.y < bestY)) {
                best = text;
                bestSize = node.size;
                bestY = node.y;
            }
        }
        return best;
    }

    /**
     * 过滤时间、电量、运营商等噪声文字
     */
    private static boolean isNoise(String text) {
        if (text.matches("^[0-9:：.\\-\\s]+$")) {
            return true;
        }
        if (text.contains("%") || text.contains("KB") || text.contains("MB")) {
            return true;
        }
        if ("5G".equals(text) || "4G".equals(text) || "WiFi".equals(text)
            || "WLAN".equals(text)) {
            return true;
        }
        // 全是标点符号
        return text.replaceAll("[\\p{Punct}\\s·—]+", "").isEmpty();
    }

    /** 界面文本节点 */
    private static final class TextNode {
        final String text;
        final float size;
        final int y;

        TextNode(String text, float size, int y) {
            this.text = text;
            this.size = size;
            this.y = y;
        }
    }

    /**
     * 把当前窗口的文字拼成一行，仅用于诊断日志
     */
    public static String describe(Activity activity) {
        try {
            var nodes = collect(activity);
            var builder = new StringBuilder();
            var decor = activity.getWindow() == null
                ? null : activity.getWindow().getDecorView();
            if (decor != null) {
                builder.append("decor=").append(decor.getWidth()).append('x')
                    .append(decor.getHeight()).append(" shown=").append(decor.isShown())
                    .append(" children=")
                    .append(decor instanceof ViewGroup ? ((ViewGroup) decor).getChildCount() : 0)
                    .append(" roots=").append(UiCallHelper.allRootViews().size())
                    .append(" nodes=").append(nodes.size()).append(" :: ");
            }
            for (var node : nodes) {
                var text = node.text;
                if (text.length() > 40) {
                    text = text.substring(0, 40);
                }
                builder.append(text).append(" | ");
                if (builder.length() > 800) {
                    break;
                }
            }
            return builder.toString();
        } catch (Throwable e) {
            return "error: " + e;
        }
    }

    /**
     * 收集来电 Activity 窗口内的可见文字
     */
    private static List<TextNode> collect(Activity activity) {
        var nodes = new ArrayList<TextNode>();
        var decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) {
            collectView(decor, nodes);
        }
        // 微信通话界面偶尔挂在同进程的独立窗口上，主窗口取不到文字时补扫一次
        if (nodes.isEmpty()) {
            for (var root : UiCallHelper.allRootViews()) {
                if (root != null && root != decor) {
                    collectView(root, nodes);
                }
            }
        }
        return nodes;
    }

    private static void collectView(View view, List<TextNode> out) {
        if (view == null || view.getVisibility() != View.VISIBLE || !view.isShown()) {
            return;
        }
        var desc = view.getContentDescription();
        if (desc != null) {
            var value = desc.toString().trim();
            if (!value.isEmpty()) {
                out.add(new TextNode(value, 0f, yOf(view)));
            }
        }
        if (view instanceof TextView) {
            var textView = (TextView) view;
            var text = textView.getText();
            if (text != null) {
                var value = text.toString().trim();
                if (!value.isEmpty()) {
                    out.add(new TextNode(value, textView.getTextSize(), yOf(view)));
                }
            }
        }
        if (view instanceof ViewGroup) {
            var group = (ViewGroup) view;
            for (var i = 0; i < group.getChildCount(); i++) {
                collectView(group.getChildAt(i), out);
            }
        }
    }

    private static int yOf(View view) {
        var loc = new int[2];
        view.getLocationOnScreen(loc);
        return loc[1];
    }

    private static boolean isOnScreen(View view) {
        var width = view.getWidth();
        var height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        var root = view.getRootView();
        var loc = new int[2];
        view.getLocationOnScreen(loc);
        var rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        var x = loc[0] - rootLoc[0];
        var y = loc[1] - rootLoc[1];
        return x + width > 0 && x < root.getWidth() && y + height > 0 && y < root.getHeight();
    }

    /**
     * 按文字查找可见节点
     */
    private static View firstByText(View view, String text, boolean exact) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view instanceof TextView) {
            var value = ((TextView) view).getText();
            if (value != null && isOnScreen(view)) {
                var content = value.toString().trim();
                if (exact ? text.equals(content) : content.startsWith(text)) {
                    return view;
                }
            }
        }
        if (view instanceof ViewGroup) {
            var group = (ViewGroup) view;
            for (var i = 0; i < group.getChildCount(); i++) {
                var found = firstByText(group.getChildAt(i), text, exact);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 按描述查找可见节点
     */
    private static View firstByDesc(View view, String desc) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return null;
        }
        var value = view.getContentDescription();
        if (value != null && value.toString().contains(desc) && isOnScreen(view)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            var group = (ViewGroup) view;
            for (var i = 0; i < group.getChildCount(); i++) {
                var found = firstByDesc(group.getChildAt(i), desc);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 依次尝试自身与可点击祖先
     */
    private static boolean clickCompat(View target) {
        try {
            if (target.isEnabled() && target.isClickable() && target.performClick()) {
                return true;
            }
            var current = target;
            for (var depth = 0; depth < 6 && current != null; depth++) {
                if (current.isEnabled() && current.isClickable() && current.performClick()) {
                    return true;
                }
                var parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
        } catch (Throwable e) {
            LogHelp.w(TAG, "voip click failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * 兜底：在按钮中心派发一次点击事件
     */
    private static boolean tapCenter(View target) {
        try {
            var root = target.getRootView();
            var loc = new int[2];
            target.getLocationOnScreen(loc);
            var rootLoc = new int[2];
            root.getLocationOnScreen(rootLoc);
            var x = loc[0] - rootLoc[0] + target.getWidth() / 2f;
            var y = loc[1] - rootLoc[1] + target.getHeight() / 2f;
            var downTime = SystemClock.uptimeMillis();
            var down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
            var up = MotionEvent.obtain(downTime, downTime + 40, MotionEvent.ACTION_UP, x, y, 0);
            root.dispatchTouchEvent(down);
            root.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
            return true;
        } catch (Throwable e) {
            LogHelp.w(TAG, "voip tap failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 兜底：接听按钮只有图标时，按界面比例点击右下角的绿色接听键
     */
    private static boolean tapAnswerByRatio(Activity activity) {
        try {
            var decor = activity.getWindow() == null
                ? null : activity.getWindow().getDecorView();
            if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
                return false;
            }
            // 实测 1080x2340 微信来电：绿色接听键中心约在 (852, 2001)
            var x = decor.getWidth() * 0.789f;
            var y = decor.getHeight() * 0.855f;
            var downTime = SystemClock.uptimeMillis();
            var down = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, x, y, 0);
            var up = MotionEvent.obtain(downTime, downTime + 40,
                MotionEvent.ACTION_UP, x, y, 0);
            decor.dispatchTouchEvent(down);
            decor.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
            LogHelp.i(TAG, "voip accept: ratio tap " + (int) x + "," + (int) y);
            return true;
        } catch (Throwable e) {
            LogHelp.w(TAG, "voip accept ratio failed: " + e);
            return false;
        }
    }

    /** 来电窗口形态：全屏通话页 / 顶部悬浮横幅 / 右上角小窗 */
    public static final String SURFACE_FULL = "full";
    public static final String SURFACE_BANNER = "banner";
    public static final String SURFACE_MINI = "mini";
    public static final String SURFACE_NONE = "none";

    /**
     * 当前来电是什么形态。判断顺序很重要：小窗/横幅的生命周期里
     * VideoActivity 可能还存在但已经失去焦点，不能只凭 Activity 判断。
     */
    public static String surfaceName(Activity activity) {
        var usable = activity != null && !activity.isDestroyed()
            && !activity.isFinishing();
        // 先看真实可见的小窗/横幅：最小化时 VideoActivity 可能仍在活动栈里，
        // 甚至还能拿到焦点，只凭 Activity 判断会误把“小窗”当成“全屏页”。
        if (findMiniRoot() != null) {
            return SURFACE_MINI;
        }
        if (findBannerRoot() != null) {
            return SURFACE_BANNER;
        }
        if (usable && activity.hasWindowFocus()) {
            return SURFACE_FULL;
        }
        // 通话页已经最小化或被微信界面盖住时，即使 Activity 还没销毁也不算全屏；
        // 否则横幅拒接成功后仍会被误判为 full，导致反复补点甚至误拒下一通来电。
        return SURFACE_NONE;
    }

    public static boolean hasBanner() {
        return findBannerRoot() != null;
    }

    public static boolean hasMini() {
        return findMiniRoot() != null;
    }

    /**
     * 悬浮来电横幅：微信在聊天列表上方画一条自定义悬浮窗，
     * 红/绿按钮不经过 Activity，只能落到最上层窗口。
     * 按钮位置按横幅自身宽高换算，避免不同分辨率/状态栏高度导致偏点。
     */
    public static boolean acceptOverlay() {
        if (clickTextInBanner("接听")) {
            LogHelp.i(TAG, "voip banner accept: text click");
            return true;
        }
        return tapBanner(0.90f, "banner accept");
    }

    public static boolean rejectOverlay() {
        if (clickTextInBanner("拒绝")) {
            LogHelp.i(TAG, "voip banner reject: text click");
            return true;
        }
        return tapBanner(0.714f, "banner reject");
    }

    /** 已接通时的悬浮横幅：右侧红色挂断键 */
    public static boolean hangupOverlay() {
        if (clickTextInBanner("挂断") || clickTextInBanner("拒绝")) {
            LogHelp.i(TAG, "voip banner hangup: text click");
            return true;
        }
        return tapBanner(0.90f, "banner hangup");
    }

    /** 横幅上的按钮带文字（实测有「接听」「拒绝」），优先按文字点，避免坐标漂移 */
    private static boolean clickTextInBanner(String text) {
        var root = findBannerRoot();
        if (root == null) {
            return false;
        }
        var view = firstByText(root, text, true);
        if (view == null) {
            view = firstByText(root, text, false);
        }
        if (view == null) {
            return false;
        }
        if (clickCompat(view)) {
            return true;
        }
        if (tapCenter(view)) {
            return true;
        }
        return false;
    }

    private static boolean tapBanner(float xRatio, String what) {
        var root = findBannerRoot();
        if (root == null) {
            LogHelp.w(TAG, "voip " + what + ": banner not visible");
            return false;
        }
        var loc = new int[2];
        root.getLocationOnScreen(loc);
        LogHelp.i(TAG, "voip " + what + " root=" + root.getClass().getName()
            + " " + root.getWidth() + "x" + root.getHeight()
            + " at " + loc[0] + "," + loc[1]);
        var tapped = tapPointOnRoot(root, xRatio, 0.50f, what);
        LogHelp.i(TAG, "voip " + what + " tapped=" + tapped);
        return tapped;
    }

    /** 从微信自己的通话通知进入通话页：通知的 contentIntent 就是微信官方入口 */
    private static boolean openCallFromNotification() {
        try {
            var ctx = android.app.AndroidAppHelper.currentApplication();
            // 关键：这里运行在微信进程，不能读模块 App 的 VoipStore；
            // 用本进程 IncomingCallMonitor 的会话状态做过滤。
            var started = IncomingCallMonitor.getCallStartedAt();
            var currentName = IncomingCallMonitor.getCallName();
            var nm = (android.app.NotificationManager) ctx.getSystemService(
                android.content.Context.NOTIFICATION_SERVICE);
            android.app.PendingIntent best = null;
            long bestTime = -1L;
            for (var sbn : nm.getActiveNotifications()) {
                if (!"com.tencent.mm".equals(sbn.getPackageName())) {
                    continue;
                }
                // 上一条通话的常驻通知不能拿来打开这一通的新来电
                if (started > 0 && sbn.getPostTime() > 0
                    && sbn.getPostTime() < started - 5_000L) {
                    continue;
                }
                var n = sbn.getNotification();
                var title = n.extras == null ? "" : String.valueOf(
                    n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE));
                var text = n.extras == null ? "" : String.valueOf(
                    n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT));
                var channel = n.getChannelId();
                var isCall = (title.contains("通话") || text.contains("通话")
                    || (channel != null && (channel.contains("voip") || channel.contains("reminder"))))
                    && !title.contains("结束") && !text.contains("结束")
                    && !title.contains("未接") && !text.contains("未接");
                var matchesName = currentName.isEmpty() || title.contains(currentName)
                    || text.contains(currentName);
                LogHelp.i(TAG, "voip notif candidate channel=" + channel
                    + " title=" + title + " text=" + text + " call=" + isCall
                    + " match=" + matchesName + " at=" + sbn.getPostTime());
                if (isCall && matchesName && n.contentIntent != null
                    && sbn.getPostTime() >= bestTime) {
                    best = n.contentIntent;
                    bestTime = sbn.getPostTime();
                }
            }
            if (best != null) {
                best.send();
                LogHelp.i(TAG, "voip open call from active notification at=" + bestTime);
                return true;
            }
            // 通知已被用户清掉：用近期抓到的 contentIntent，仍在有效期内就还能打开
            var cached = com.cdtubear.xpwechatcall.hook.VoipHook.cachedCallPendingIntent();
            var cachedAt = com.cdtubear.xpwechatcall.hook.VoipHook.cachedCallPendingAt();
            var now = System.currentTimeMillis();
            if (cached != null && now - cachedAt <= 90_000L
                && (started <= 0 || cachedAt >= started - 5_000L)) {
                cached.send();
                LogHelp.i(TAG, "voip open call from cached notification at=" + cachedAt);
                return true;
            }
        } catch (Throwable e) {
            LogHelp.w(TAG, "voip open call from notification failed: " + e);
        }
        return false;
    }

    /** 小窗来电：通过微信通话通知的官方入口恢复全屏通话页 */
    public static boolean expandMiniWindow() {
        if (openCallFromNotification()) {
            return true;
        }
        // 通知被用户清掉时，用实测坐标兜底（需要 root）
        var display = android.app.AndroidAppHelper.currentApplication()
            .getResources().getDisplayMetrics();
        return rootTap(display.widthPixels * 0.89f, display.heightPixels * 0.13f);
    }

    /**
     * 找到顶部整条来电横幅。特征：接近全屏宽、高度较矮、位于屏幕上方。
     * 排除状态栏（高度过小）和全屏 Activity（宽高都接近屏幕）。
     */
    private static View findBannerRoot() {
        try {
            var metrics = android.app.AndroidAppHelper.currentApplication()
                .getResources().getDisplayMetrics();
            var screenW = metrics.widthPixels;
            var screenH = metrics.heightPixels;
            View best = null;
            for (var root : UiCallHelper.allRootViews()) {
                if (root == null || !root.isShown()) {
                    continue;
                }
                var w = root.getWidth();
                var h = root.getHeight();
                var loc = new int[2];
                root.getLocationOnScreen(loc);
                if (w < screenW * 0.75f || h < screenH * 0.05f
                    || h > screenH * 0.35f || loc[1] > screenH * 0.30f) {
                    continue;
                }
                if (w >= screenW * 0.95f && h >= screenH * 0.85f) {
                    continue;
                }
                // 横幅是横条，不是竖长条
                if (h > w * 0.6f) {
                    continue;
                }
                if (best == null || w > best.getWidth()) {
                    best = root;
                }
            }
            return best;
        } catch (Throwable e) {
            LogHelp.w(TAG, "find banner failed: " + e);
            return null;
        }
    }

    /** 右上角小窗：尺寸小、贴右边、位于屏幕上部 */
    private static View findMiniRoot() {
        try {
            var metrics = android.app.AndroidAppHelper.currentApplication()
                .getResources().getDisplayMetrics();
            var screenW = metrics.widthPixels;
            var screenH = metrics.heightPixels;
            View best = null;
            int[] bestLoc = null;
            for (var root : UiCallHelper.allRootViews()) {
                if (root == null || !root.isShown()) {
                    continue;
                }
                var w = root.getWidth();
                var h = root.getHeight();
                var loc = new int[2];
                root.getLocationOnScreen(loc);
                LogHelp.i(TAG, "voip mini candidate " + root.getClass().getName()
                    + " " + w + "x" + h + " at " + loc[0] + "," + loc[1]);
                if (w < screenW * 0.10f || w > screenW * 0.38f
                    || h < screenH * 0.05f || h > screenH * 0.32f) {
                    continue;
                }
                if (loc[0] < screenW * 0.60f || loc[1] > screenH * 0.30f) {
                    continue;
                }
                // 同区域可能还有微信聊天浮球：通话小窗位置更靠上，取最靠上的候选
                if (best == null || loc[1] < bestLoc[1]
                    || (loc[1] == bestLoc[1] && w > best.getWidth())) {
                    best = root;
                    bestLoc = loc;
                }
            }
            if (best != null) {
                LogHelp.i(TAG, "voip mini root=" + best.getClass().getName()
                    + " " + best.getWidth() + "x" + best.getHeight()
                    + " at " + bestLoc[0] + "," + bestLoc[1]);
            }
            return best;
        } catch (Throwable e) {
            LogHelp.w(TAG, "find mini failed: " + e);
        }
        return null;
    }

    /**
     * 在指定窗口内点击一个比例位置。
     *
     * 先找该点下最小的可点击子 View 直接 performClick，再派发触摸，
     * 都不生效时用 root 的 input tap 注入。这样横幅按钮不依赖固定屏幕坐标，
     * 小窗也不会因为 dispatchTouchEvent 被吞掉而一直点不开。
     */
    private static boolean tapPointOnRoot(View root, float xRatio, float yRatio, String what) {
        var loc = new int[2];
        root.getLocationOnScreen(loc);
        var x = loc[0] + root.getWidth() * xRatio;
        var y = loc[1] + root.getHeight() * yRatio;
        // 先点具体子控件，再用系统级 input tap；最后才 dispatchTouchEvent。
        // 微信小窗/横幅的根窗口会“消费”dispatch 但不执行动作，顺序反了会假成功。
        if (clickChildAt(root, x, y)) {
            LogHelp.i(TAG, "voip " + what + ": child click " + (int) x + "," + (int) y);
            return true;
        }
        if (rootTap(x, y)) {
            LogHelp.i(TAG, "voip " + what + ": root tap " + (int) x + "," + (int) y);
            return true;
        }
        if (dispatchTap(root, x, y)) {
            LogHelp.i(TAG, "voip " + what + ": dispatch " + (int) x + "," + (int) y);
            return true;
        }
        return false;
    }

    /** 点击屏幕坐标下最小的可点击控件（按钮本体通常比整条横幅小） */
    private static boolean clickChildAt(View root, float x, float y) {
        try {
            var views = new ArrayList<View>();
            collectViews(root, views);
            View best = null;
            var bestArea = Integer.MAX_VALUE;
            for (var view : views) {
                if (view == root || !view.isShown() || !view.isEnabled()
                    || !view.isClickable()) {
                    continue;
                }
                var loc = new int[2];
                view.getLocationOnScreen(loc);
                var w = view.getWidth();
                var h = view.getHeight();
                if (w <= 0 || h <= 0 || x < loc[0] || x > loc[0] + w
                    || y < loc[1] || y > loc[1] + h) {
                    continue;
                }
                var area = w * h;
                if (area < bestArea) {
                    bestArea = area;
                    best = view;
                }
            }
            if (best != null && best.performClick()) {
                return true;
            }
        } catch (Throwable e) {
            LogHelp.w(TAG, "click child failed: " + e);
        }
        return false;
    }

    private static boolean dispatchTap(View root, float x, float y) {
        try {
            var loc = new int[2];
            root.getLocationOnScreen(loc);
            var localX = x - loc[0];
            var localY = y - loc[1];
            var downTime = SystemClock.uptimeMillis();
            var down = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, localX, localY, 0);
            var up = MotionEvent.obtain(downTime, downTime + 40,
                MotionEvent.ACTION_UP, localX, localY, 0);
            var consumed = root.dispatchTouchEvent(down);
            SystemClock.sleep(40);
            consumed = root.dispatchTouchEvent(up) || consumed;
            down.recycle();
            up.recycle();
            return consumed;
        } catch (Throwable e) {
            LogHelp.w(TAG, "dispatch tap failed: " + e);
            return false;
        }
    }

    /**
     * 微信小窗是 FloatBall 自绘窗口，dispatchTouchEvent 实测不触发点击，
     * root 环境下用 input tap 注入真实触摸最稳。
     */
    private static boolean rootTap(float x, float y) {
        try {
            // Magisk 的 su 在 /debug_ramdisk/su，应用进程 PATH 里没有 su；
            // 直接用 "su" 会报 error=13 Permission denied。
            var su = new java.io.File("/debug_ramdisk/su");
            var shell = su.exists() ? su.getAbsolutePath() : "su";
            var process = Runtime.getRuntime().exec(new String[]{
                shell, "-c", "input tap " + (int) x + " " + (int) y});
            var finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            var code = finished ? process.exitValue() : -1;
            LogHelp.i(TAG, "voip root tap " + (int) x + "," + (int) y
                + " code=" + code);
            return finished && code == 0;
        } catch (Throwable e) {
            LogHelp.w(TAG, "voip root tap failed: " + e);
            return false;
        }
    }

    /**
     * 优先使用来电 Activity 自己的窗口，找不到时再退回全部窗口
     */
    private static List<View> roots(Activity activity) {
        var roots = new ArrayList<View>();
        var decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) {
            roots.add(decor);
        }
        for (var root : UiCallHelper.allRootViews()) {
            if (root != null && root != decor) {
                roots.add(root);
            }
        }
        return roots;
    }
}
