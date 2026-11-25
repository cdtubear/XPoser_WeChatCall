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

    private static boolean isOutgoingRecent() {
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
        if (target == null) {
            // 新版微信接听按钮只有图标，按位置找右下角的大圆按钮
            target = findAnswerByGeometry(activity);
        }
        if (target == null) {
            if (tapAnswerByRatio(activity)) {
                LogHelp.i(TAG, "voip accept: ratio tap ok");
                return true;
            }
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
            var x = decor.getWidth() * 0.94f;
            var y = decor.getHeight() * 0.89f;
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
