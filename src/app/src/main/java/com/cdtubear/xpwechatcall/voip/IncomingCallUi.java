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

        Info(String name, boolean video) {
            this.name = name;
            this.video = video;
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
        if (!hasAccept(nodes)) {
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

    /**
     * 是否仍在响铃（界面上还有「接听」按钮）
     */
    public static boolean isRinging(Activity activity) {
        if (activity == null) {
            return false;
        }
        return hasAccept(collect(activity));
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
     * 收集来电 Activity 窗口内的可见文字
     */
    private static List<TextNode> collect(Activity activity) {
        var nodes = new ArrayList<TextNode>();
        var decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) {
            collectView(decor, nodes);
        }
        return nodes;
    }

    private static void collectView(View view, List<TextNode> out) {
        if (view == null || view.getVisibility() != View.VISIBLE || !isOnScreen(view)) {
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
