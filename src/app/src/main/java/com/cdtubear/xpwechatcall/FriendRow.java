package com.cdtubear.xpwechatcall;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.zgcwkj.comm.Friend;
import com.zgcwkj.comm.FriendSync;

/**
 * 好友列表行：负责好友数据的视图绑定。
 * 图标只解码到列表实际需要的尺寸并做内存缓存，配合 ListView 的视图复用避免滚动掉帧。
 */
public class FriendRow {

    private static final int AVATAR_PX = 144;
    private static final LruCache<String, Bitmap> ICON_CACHE =
        new LruCache<>(Math.min(8 * 1024 * 1024, (int) (Runtime.getRuntime().maxMemory() / 8)));

    /**
     * 行操作回调
     */
    public interface Listener {
        void onShortcut(Friend friend);

        void onEdit(Friend friend);

        void onDelete(Friend friend);
    }

    /**
     * 创建一行好友视图
     */
    public static View inflate(ViewGroup parent) {
        return LayoutInflater.from(parent.getContext()).inflate(R.layout.item_friend, parent, false);
    }

    /**
     * 把好友数据绑定到行视图上。
     * 整行点击 = 创建通话图标，长按 = 编辑；更多操作收纳编辑/删除，避免每行堆满按钮。
     */
    public static void bind(View view, Friend friend, Listener listener) {
        var context = view.getContext();
        var avatar = (TextView) view.findViewById(R.id.avatar);
        var icon = iconFor(context, friend);
        if (icon != null) {
            avatar.setText("");
            avatar.setBackground(new BitmapDrawable(view.getResources(), icon));
        } else {
            avatar.setText(firstChar(friend.nickname));
            avatar.setBackgroundResource(R.drawable.bg_avatar);
        }
        ((TextView) view.findViewById(R.id.nickname)).setText(friend.nickname);
        ((TextView) view.findViewById(R.id.wxid)).setText(friend.wxid);

        var shortcut = view.findViewById(R.id.btn_shortcut);
        shortcut.setContentDescription(context.getString(R.string.shortcut_desc, friend.nickname));
        shortcut.setOnClickListener(v -> listener.onShortcut(friend));

        var more = view.findViewById(R.id.btn_more);
        more.setContentDescription(context.getString(R.string.more_desc, friend.nickname));
        more.setOnClickListener(v -> showMoreMenu(v, friend, listener));

        view.setOnClickListener(v -> listener.onShortcut(friend));
        view.setOnLongClickListener(v -> {
            listener.onEdit(friend);
            return true;
        });
        view.setVisibility(View.VISIBLE);
    }

    private static void showMoreMenu(View anchor, Friend friend, Listener listener) {
        var menu = new PopupMenu(anchor.getContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.action_edit);
        menu.getMenu().add(0, 2, 1, R.string.action_delete);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                listener.onEdit(friend);
                return true;
            }
            if (item.getItemId() == 2) {
                listener.onDelete(friend);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private static Bitmap iconFor(android.content.Context context, Friend friend) {
        var key = friend.wxid + "|" + (friend.iconPath == null ? "" : friend.iconPath);
        var cached = ICON_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        var raw = FriendSync.loadIcon(context, friend, AVATAR_PX);
        if (raw == null) {
            return null;
        }
        var rounded = circle(raw);
        ICON_CACHE.put(key, rounded);
        return rounded;
    }

    private static Bitmap circle(Bitmap source) {
        var size = Math.min(source.getWidth(), source.getHeight());
        var output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        var canvas = new Canvas(output);
        var paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        var radius = size / 2f;
        canvas.drawCircle(radius, radius, radius, paint);
        return output;
    }

    private static String firstChar(String name) {
        if (name == null || name.isEmpty()) {
            return "微";
        }
        return name.substring(0, 1);
    }
}
