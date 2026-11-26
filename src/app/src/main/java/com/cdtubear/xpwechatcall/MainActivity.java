package com.cdtubear.xpwechatcall;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import com.zgcwkj.comm.ConfigHelp;
import com.zgcwkj.comm.Friend;
import com.zgcwkj.comm.FriendSync;
import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.ShortcutHelp;
import com.cdtubear.xpwechatcall.widget.CallWidgetProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：管理好友列表并为好友创建一键通话桌面图标
 */
public class MainActivity extends Activity {

    /** 选择图标请求码 */
    private static final int REQ_PICK_ICON = 1001;

    /** 底部导航：首页 / 日志 / 设置 */
    public static final int TAB_HOME = 0;
    public static final int TAB_LOG = 1;
    public static final int TAB_SETTINGS = 2;
    /** 外部跳转时指定要展示的 Tab */
    public static final String EXTRA_TAB = "extra_tab";

    /** 全部好友，与配置文件内容一致 */
    private final List<Friend> friends = new ArrayList<>();
    /** 按搜索关键字过滤后实际展示的好友 */
    private final List<Friend> shown = new ArrayList<>();
    /** 列表行操作回调 */
    private FriendRow.Listener rowListener;
    /** 虚拟化列表与状态视图 */
    private ListView listView;
    private FriendAdapter adapter;
    private View listSurface;
    private View loadingView;
    private View emptyView;
    private View searchEmptyView;
    private View errorView;
    private View successBanner;
    private TextView successText;
    private TextView errorTitle;
    private TextView errorText;
    private TextView friendCount;
    private View pageHome;
    private View pageLog;
    private View tabHomeIcon;
    private View tabHomeText;
    private View tabLogIcon;
    private View tabLogText;
    private View tabSettingsIcon;
    private View tabSettingsText;
    private TextView pageTitle;
    private TextView pageSubtitle;
    private TextView logText;
    private View logEmptyView;
    private View logScroll;
    private View btnImport;
    private View btnImportIcon;
    private TextView btnImportText;
    private View btnAdd;
    private View btnLogRefresh;
    private View btnLogClear;
    private Switch logSwitch;
    private EditText searchInput;
    private View searchClear;
    private View btnEmptyImport;
    private View btnEmptyAdd;
    private View btnClearSearch;
    private View btnRetry;
    private boolean importing;
    private Runnable errorRetry;
    private Runnable hideBanner;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 最近一次错误是否覆盖内容区；搜索或操作成功时清除 */
    private boolean showingError;

    /** 编辑弹窗中的图标预览与当前选中的图标路径 */
    private ImageView dialogIcon;
    private String pendingIconPath;
    private String pendingIconKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        var logDir = new File(getFilesDir(), "logs");
        LogHelp.init(logDir, ConfigHelp.isLogEnabled(this));
        // 沉浸式：内容延伸到状态栏与导航栏之下，再按实际系统栏尺寸给内部控件补内边距
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_main);
        applyWindowInsets();

        listView = findViewById(R.id.list_view);
        listSurface = findViewById(R.id.list_surface);
        loadingView = findViewById(R.id.loading_view);
        emptyView = findViewById(R.id.empty_view);
        searchEmptyView = findViewById(R.id.search_empty_view);
        errorView = findViewById(R.id.error_view);
        errorTitle = findViewById(R.id.error_title);
        errorText = findViewById(R.id.error_text);
        successBanner = findViewById(R.id.success_banner);
        successText = findViewById(R.id.success_text);
        friendCount = findViewById(R.id.friend_count);
        pageHome = findViewById(R.id.page_home);
        pageLog = findViewById(R.id.page_log);
        pageTitle = findViewById(R.id.page_title);
        pageSubtitle = findViewById(R.id.page_subtitle);
        tabHomeIcon = findViewById(R.id.tab_home_icon);
        tabHomeText = findViewById(R.id.tab_home_text);
        tabLogIcon = findViewById(R.id.tab_log_icon);
        tabLogText = findViewById(R.id.tab_log_text);
        tabSettingsIcon = findViewById(R.id.tab_settings_icon);
        tabSettingsText = findViewById(R.id.tab_settings_text);
        logText = findViewById(R.id.log_text);
        logEmptyView = findViewById(R.id.log_empty_view);
        logScroll = findViewById(R.id.log_scroll);
        btnImport = findViewById(R.id.btn_import);
        btnImportIcon = findViewById(R.id.btn_import_icon);
        btnImportText = findViewById(R.id.btn_import_text);
        btnAdd = findViewById(R.id.btn_add);
        btnLogRefresh = findViewById(R.id.btn_log_refresh);
        btnLogClear = findViewById(R.id.btn_log_clear);
        logSwitch = findViewById(R.id.log_switch);
        btnEmptyImport = findViewById(R.id.btn_empty_import);
        btnEmptyAdd = findViewById(R.id.btn_empty_add);
        btnClearSearch = findViewById(R.id.btn_clear_search);
        btnRetry = findViewById(R.id.btn_retry);

        var textLogPath = (TextView) findViewById(R.id.log_path);
        textLogPath.setText(logDir.getAbsolutePath());

        // 搜索框：输入即过滤，右侧按钮一键清空
        searchInput = findViewById(R.id.search_input);
        searchClear = findViewById(R.id.search_clear);
        searchClear.setOnClickListener(v -> clearSearch());
        btnClearSearch.setOnClickListener(v -> clearSearch());
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // 用户重新搜索时退出错误态，避免错误视图盖住可恢复的列表
                showingError = false;
                refresh();
                if (listView != null) {
                    listView.setSelection(0);
                }
            }
        });

        btnAdd.setOnClickListener(v -> showFriendDialog(null));
        btnEmptyAdd.setOnClickListener(v -> showFriendDialog(null));
        btnImport.setOnClickListener(v -> importFromWeChat());
        btnEmptyImport.setOnClickListener(v -> importFromWeChat());
        btnRetry.setOnClickListener(v -> {
            if (errorRetry != null) {
                errorRetry.run();
            } else {
                importFromWeChat();
            }
        });
        btnLogRefresh.setOnClickListener(v -> showLog());
        btnLogClear.setOnClickListener(v -> clearLog());
        findViewById(R.id.tab_home).setOnClickListener(v -> switchTab(TAB_HOME));
        findViewById(R.id.tab_log).setOnClickListener(v -> switchTab(TAB_LOG));
        findViewById(R.id.tab_settings).setOnClickListener(v -> openSettings());

        // 文件日志开关：与配置保持一致，切换后立即生效
        logSwitch.setChecked(ConfigHelp.isLogEnabled(this));
        logSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigHelp.setLogEnabled(this, isChecked);
            LogHelp.setEnabled(isChecked);
            LogHelp.i("XpWeChatCall", "file log " + (isChecked ? "enabled" : "disabled"));
            showLog();
        });

        loadFriends();

        rowListener = new FriendRow.Listener() {
            @Override
            public void onShortcut(Friend friend) {
                // 创建前先让用户选择视频或语音通话
                showCallTypeDialog(friend);
            }

            @Override
            public void onEdit(Friend friend) {
                showFriendDialog(friend);
            }

            @Override
            public void onDelete(Friend friend) {
                confirmDelete(friend);
            }
        };
        // 底部版权信息：点击空白区域跳转项目主页，文字内的链接由 LinkMovementMethod 处理
        findViewById(R.id.tv_footer).setOnClickListener(v -> startActivity(new Intent(
            Intent.ACTION_VIEW, Uri.parse(getString(R.string.author_url)))));

        adapter = new FriendAdapter();
        listView.setAdapter(adapter);

        registerBackHandler();
        switchTab(tabFromIntent(getIntent()));
        refresh();
        LogHelp.i("XpWeChatCall", "main page opened, friends=" + friends.size());
    }

    /**
     * Android 13+ 使用系统返回手势回调；旧版本继续走 onBackPressed 兼容路径。
     */
    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::performBack);
        }
    }

    private void performBack() {
        if (pageLog != null && pageLog.getVisibility() == View.VISIBLE) {
            switchTab(TAB_HOME);
            return;
        }
        finish();
    }

    /**
     * 旧版本返回键兼容。日志页不是独立页面，返回键先回到好友页。
     */
    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        performBack();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        switchTab(tabFromIntent(intent));
    }

    /**
     * 读取 Intent 中指定的 Tab，默认首页
     */
    private int tabFromIntent(Intent intent) {
        if (intent == null) {
            return TAB_HOME;
        }
        var tab = intent.getIntExtra(EXTRA_TAB, TAB_HOME);
        return tab == TAB_LOG ? TAB_LOG : TAB_HOME;
    }

    /**
     * 打开设置页：设置与首页、日志同级，独立页面复用同一套底部导航
     */
    private void openSettings() {
        var intent = new Intent(this, TriggerSettingsActivity.class);
        intent.putExtra(TriggerSettingsActivity.EXTRA_TAB, TriggerSettingsActivity.TAB_SETTINGS);
        startActivity(intent);
    }

    /**
     * 沉浸式布局
     *
     * 标题栏与底部导航延伸到系统栏区域，文字/列表按实际系统栏尺寸内缩，
     * 横屏时左右系统栏也不会遮住内容。
     */
    private void applyWindowInsets() {
        var root = findViewById(R.id.root);
        var spacer = findViewById(R.id.status_bar_spacer);
        var titleBar = findViewById(R.id.title_bar);
        var content = findViewById(R.id.content);
        var tabBar = findViewById(R.id.tab_bar);
        var tabHeight = getResources().getDimensionPixelSize(R.dimen.tab_bar_height);
        // 标题栏/导航原有内边距，系统栏内边距叠加在它之上，避免覆盖内部留白
        var titlePaddingStart = titleBar.getPaddingStart();
        var titlePaddingEnd = titleBar.getPaddingEnd();
        var titlePaddingTop = titleBar.getPaddingTop();
        var titlePaddingBottom = titleBar.getPaddingBottom();
        var tabPaddingStart = tabBar.getPaddingStart();
        var tabPaddingEnd = tabBar.getPaddingEnd();
        var tabPaddingTop = tabBar.getPaddingTop();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            // 刘海屏的挖孔区域也计入，避免内容被遮挡
            var bars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            var spacerParams = spacer.getLayoutParams();
            spacerParams.height = bars.top;
            spacer.setLayoutParams(spacerParams);
            titleBar.setPaddingRelative(titlePaddingStart + bars.left, titlePaddingTop,
                titlePaddingEnd + bars.right, titlePaddingBottom);
            content.setPadding(bars.left, 0, bars.right, 0);
            tabBar.setPadding(tabPaddingStart + bars.left, tabPaddingTop,
                tabPaddingEnd + bars.right, bars.bottom + 6);
            var tabParams = tabBar.getLayoutParams();
            tabParams.height = tabHeight + bars.bottom;
            tabBar.setLayoutParams(tabParams);
            return insets;
        });
    }

    /**
     * 切换底部 Tab
     *
     * 设置页是独立 Activity，由底部导航直接打开，这里只处理首页与日志。
     *
     * @param tab {@link #TAB_HOME} 或 {@link #TAB_LOG}
     */
    private void switchTab(int tab) {
        var home = tab != TAB_LOG;
        pageHome.setVisibility(home ? View.VISIBLE : View.GONE);
        pageLog.setVisibility(home ? View.GONE : View.VISIBLE);
        pageTitle.setText(home ? R.string.home_title : R.string.log_title);
        pageSubtitle.setText(home ? R.string.home_subtitle : R.string.log_subtitle);

        var primary = getResources().getColor(R.color.primary);
        var secondary = getResources().getColor(R.color.text_secondary);
        var homeTab = findViewById(R.id.tab_home);
        var logTab = findViewById(R.id.tab_log);
        var settingsTab = findViewById(R.id.tab_settings);
        homeTab.setSelected(home);
        logTab.setSelected(!home);
        settingsTab.setSelected(false);

        ((TextView) tabHomeText).setTextColor(home ? primary : secondary);
        ((TextView) tabLogText).setTextColor(home ? secondary : primary);
        ((TextView) tabSettingsText).setTextColor(secondary);
        ((ImageView) tabHomeIcon).setImageTintList(
            android.content.res.ColorStateList.valueOf(home ? primary : secondary));
        ((ImageView) tabLogIcon).setImageTintList(
            android.content.res.ColorStateList.valueOf(home ? secondary : primary));
        ((ImageView) tabSettingsIcon).setImageTintList(
            android.content.res.ColorStateList.valueOf(secondary));

        // 右上角操作随 Tab 切换：首页=导入/添加，日志=刷新/清空
        btnImport.setVisibility(home ? View.VISIBLE : View.GONE);
        btnAdd.setVisibility(home ? View.VISIBLE : View.GONE);
        btnLogRefresh.setVisibility(home ? View.GONE : View.VISIBLE);
        btnLogClear.setVisibility(home ? View.GONE : View.VISIBLE);

        if (!home) {
            showLog();
        }
    }

    /**
     * 显示日志文件内容
     */
    private void showLog() {
        var text = LogHelp.readAll();
        var hasLog = text != null && !text.trim().isEmpty();
        logText.setText(hasLog ? text : "");
        logEmptyView.setVisibility(hasLog ? View.GONE : View.VISIBLE);
        if (hasLog) {
            logText.post(() -> ((android.widget.ScrollView) logScroll).fullScroll(View.FOCUS_DOWN));
        }
    }

    /**
     * 清空日志
     */
    private void clearLog() {
        var dir = LogHelp.logDir();
        if (dir == null) {
            return;
        }
        var day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
            .format(new java.util.Date());
        var file = new java.io.File(dir, day + ".log");
        if (file.exists()) {
            file.delete();
        }
        showLog();
        showSuccess(getString(R.string.log_cleared));
    }

    /**
     * 从配置读取好友列表
     */
    private void loadFriends() {
        friends.clear();
        var array = ConfigHelp.getFriends(this);
        if (array != null) {
            for (var i = 0; i < array.length(); i++) {
                var friend = Friend.fromJson(array.optJSONObject(i));
                if (friend != null && !friend.wxid.isEmpty()) {
                    friends.add(friend);
                }
            }
        }
    }

    /**
     * 保存好友列表到配置
     */
    private void saveFriends() {
        var array = new JSONArray();
        for (var friend : friends) {
            array.put(friend.toJson());
        }
        ConfigHelp.setFriends(this, array);
        // 好友信息或图标变化后同步刷新桌面控件
        CallWidgetProvider.updateAll(this);
    }

    /**
     * 刷新列表显示和空状态。
     * 正常内容、空列表、搜索无结果、错误四种状态互斥，避免内容互相盖住。
     */
    private void refresh() {
        applyFilter();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        friendCount.setText(getString(R.string.friend_count, friends.size()));

        if (importing || showingError) {
            return;
        }

        var noFriends = friends.isEmpty();
        var hasRows = !shown.isEmpty();
        listSurface.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        loadingView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        emptyView.setVisibility(noFriends ? View.VISIBLE : View.GONE);
        searchEmptyView.setVisibility(!noFriends && !hasRows ? View.VISIBLE : View.GONE);
    }

    /**
     * 导入/首次读取时的骨架屏；按钮同步进入 disabled 状态。
     */
    private void setImporting(boolean busy) {
        importing = busy;
        btnImport.setEnabled(!busy);
        btnAdd.setEnabled(!busy);
        btnEmptyImport.setEnabled(!busy);
        btnImport.setAlpha(busy ? 0.55f : 1f);
        btnAdd.setAlpha(busy ? 0.55f : 1f);
        btnImportText.setText(busy ? R.string.action_importing : R.string.action_import_short);
        btnImportIcon.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);

        if (busy) {
            showingError = false;
            listSurface.setVisibility(View.GONE);
            emptyView.setVisibility(View.GONE);
            searchEmptyView.setVisibility(View.GONE);
            errorView.setVisibility(View.GONE);
            loadingView.setVisibility(View.VISIBLE);
            friendCount.setText(R.string.action_importing);
            return;
        }
        loadingView.setVisibility(View.GONE);
        refresh();
    }

    /**
     * 错误状态：说明原因并保留一个明确的重试入口。
     */
    private void showError(CharSequence message, Runnable retry) {
        importing = false;
        showingError = true;
        setImporting(false);
        errorRetry = retry;
        errorTitle.setText(R.string.error_import_title);
        errorText.setText(message);
        friendCount.setText("—");
        listSurface.setVisibility(View.GONE);
        loadingView.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        searchEmptyView.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
    }

    /**
     * 成功反馈：自动消失的轻量 banner；系统关闭动画时直接显示，不强行播放。
     */
    private void showSuccess(CharSequence text) {
        showingError = false;
        successText.setText(text);
        successBanner.setVisibility(View.VISIBLE);
        if (hideBanner != null) {
            ui.removeCallbacks(hideBanner);
        }
        successBanner.animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            successBanner.setAlpha(1f);
            successBanner.setTranslationY(0f);
        } else {
            successBanner.setAlpha(0f);
            successBanner.setTranslationY(-8f);
            successBanner.animate().alpha(1f).translationY(0f).setDuration(200).start();
        }
        hideBanner = () -> {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                successBanner.setVisibility(View.GONE);
                return;
            }
            successBanner.animate().alpha(0f).translationY(-8f).setDuration(160)
                .withEndAction(() -> successBanner.setVisibility(View.GONE)).start();
        };
        ui.postDelayed(hideBanner, 2400);
    }

    private void clearSearch() {
        showingError = false;
        searchInput.setText("");
        refresh();
    }

    /**
     * 按搜索框内容重建展示列表
     * 关键字同时匹配备注与 wxid，忽略大小写
     */
    private void applyFilter() {
        var keyword = searchInput.getText().toString().trim().toLowerCase(Locale.getDefault());
        shown.clear();
        for (var friend : friends) {
            if (keyword.isEmpty()
                || friend.nickname.toLowerCase(Locale.getDefault()).contains(keyword)
                || friend.wxid.toLowerCase(Locale.getDefault()).contains(keyword)) {
                shown.add(friend);
            }
        }
        searchClear.setVisibility(keyword.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * 虚拟化好友列表：只创建屏幕上可见的行，滚动时复用 convertView。
     */
    private class FriendAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            var row = convertView;
            if (row == null) {
                row = FriendRow.inflate(parent);
            }
            FriendRow.bind(row, (Friend) getItem(position), rowListener);
            return row;
        }
    }

    /**
     * 选择通话类型后再创建桌面图标
     * 首页点击好友时弹出，用户选择视频或语音后按该类型创建图标
     */
    private void showCallTypeDialog(Friend friend) {
        var options = new CharSequence[]{
            getString(R.string.call_type_video),
            getString(R.string.call_type_voice)
        };
        new AlertDialog.Builder(this)
            .setTitle(friend.nickname)
            .setItems(options, (dialog, which) -> {
                friend.callType = (which == 0) ? ShortcutHelp.CALL_VIDEO : ShortcutHelp.CALL_VOICE;
                saveFriends();
                createShortcut(friend);
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    /**
     * 为好友创建桌面通话图标
     */
    private void createShortcut(Friend friend) {
        var ok = ShortcutHelp.requestShortcut(this, friend);
        showSuccess(getString(ok ? R.string.toast_shortcut_requested : R.string.toast_shortcut_unsupported));
    }

    /**
     * 删除好友前二次确认
     */
    private void confirmDelete(Friend friend) {
        new AlertDialog.Builder(this)
            .setTitle(friend.nickname)
            .setMessage(R.string.action_delete + "?")
            .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                friends.remove(friend);
                saveFriends();
                refresh();
                showSuccess(getString(R.string.toast_deleted));
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    /**
     * 新增或编辑好友弹窗
     *
     * @param existing 为 null 时表示新增，否则为编辑
     */
    private void showFriendDialog(Friend existing) {
        var view = LayoutInflater.from(this).inflate(R.layout.dialog_friend, null);
        var etNickname = (EditText) view.findViewById(R.id.et_nickname);
        var etWxid = (EditText) view.findViewById(R.id.et_wxid);
        dialogIcon = view.findViewById(R.id.iv_icon);

        // 通话类型不在此处选择，列表只保存好友；创建桌面图标时再选视频或语音
        pendingIconPath = "";
        pendingIconKey = "";
        if (existing != null) {
            etNickname.setText(existing.nickname);
            etWxid.setText(existing.wxid);
            pendingIconPath = existing.iconPath == null ? "" : existing.iconPath;
            pendingIconKey = existing.wxid;
        }
        showDialogIcon(etNickname.getText().toString(), etWxid.getText().toString());

        view.findViewById(R.id.btn_pick_icon).setOnClickListener(v -> pickIcon(etWxid));
        view.findViewById(R.id.btn_reset_icon).setOnClickListener(v -> {
            FriendSync.deleteIcon(pendingIconPath);
            pendingIconPath = "";
            showDialogIcon(etNickname.getText().toString(), etWxid.getText().toString());
        });

        var dialog = new AlertDialog.Builder(this)
            .setTitle(existing == null ? R.string.dialog_add_title : R.string.dialog_edit_title)
            .setView(view)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            var nickname = etNickname.getText().toString().trim();
            var wxid = etWxid.getText().toString().trim();
            if (nickname.isEmpty()) {
                toast(R.string.toast_nickname_required);
                return;
            }
            if (wxid.isEmpty()) {
                toast(R.string.toast_wxid_required);
                return;
            }
            if (existing == null) {
                var friend = new Friend(nickname, wxid, ShortcutHelp.CALL_VIDEO);
                friend.iconPath = pendingIconPath;
                friends.add(friend);
            } else {
                existing.nickname = nickname;
                existing.wxid = wxid;
                existing.iconPath = pendingIconPath;
            }
            saveFriends();
            refresh();
            showSuccess(getString(R.string.toast_saved));
            dialog.dismiss();
        }));
        dialog.show();
    }

    /**
     * 选择自定义图标
     */
    private void pickIcon(EditText etWxid) {
        var key = etWxid.getText().toString().trim();
        pendingIconKey = key.isEmpty() ? "icon" : key;
        try {
            var intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQ_PICK_ICON);
        } catch (Exception e) {
            LogHelp.e("XpWeChatCall", "pick icon failed: " + e.getMessage(), e);
            toast(R.string.toast_pick_icon_failed);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_ICON || resultCode != RESULT_OK || data == null) {
            return;
        }
        var path = FriendSync.saveIcon(this, pendingIconKey, data.getData());
        if (path == null) {
            toast(R.string.toast_pick_icon_failed);
            return;
        }
        FriendSync.deleteIcon(pendingIconPath);
        pendingIconPath = path;
        if (dialogIcon != null) {
            showDialogIcon("", "");
        }
    }

    /**
     * 刷新编辑弹窗中的图标预览
     */
    private void showDialogIcon(String nickname, String wxid) {
        if (dialogIcon == null) {
            return;
        }
        var friend = new Friend(nickname, wxid, ShortcutHelp.CALL_VIDEO);
        friend.iconPath = pendingIconPath;
        var bitmap = FriendSync.loadIcon(this, friend, 160);
        if (bitmap != null) {
            dialogIcon.setImageBitmap(bitmap);
        } else {
            dialogIcon.setImageDrawable(null);
        }
    }

    /**
     * 从微信导入好友
     * 好友数据由微信进程中的 Hook 读取 rcontact 表后回传。
     * MIUI 等系统会拦截微信拉起本应用，因此先由本应用（处于前台）主动拉起微信，
     * 触发 Hook 读取并回传，再轮询等待回传结果。
     */
    private void importFromWeChat() {
        if (importing) {
            return;
        }
        var before = FriendSync.lastSyncTime(this);
        setImporting(true);
        if (!launchWeChatForImport()) {
            setImporting(false);
            if (friends.isEmpty()) {
                showError(getString(R.string.toast_wechat_missing), this::importFromWeChat);
            } else {
                toast(R.string.toast_wechat_missing);
            }
            return;
        }
        toast(R.string.toast_import_waiting);
        pollFriendsForImport(before, 0);
    }

    /**
     * 拉起微信主界面，触发 Hook 读取好友并回传
     */
    private boolean launchWeChatForImport() {
        try {
            var intent = new Intent();
            intent.setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            LogHelp.e("XpWeChatCall", "launch wechat for import failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 轮询等待微信回传好友数据，超时后按现有数据导入
     */
    private void pollFriendsForImport(long before, int attempt) {
        if (attempt >= 20) {
            doImportFromWeChat();
            return;
        }
        ui.postDelayed(() -> {
            if (FriendSync.lastSyncTime(this) > before) {
                doImportFromWeChat();
            } else {
                pollFriendsForImport(before, attempt + 1);
            }
        }, 500);
    }

    /**
     * 镜像导入：清空本地好友后完全以微信数据为准
     *
     * 同一 wxid 已选过的通话类型会被保留，避免用户重复设置。
     */
    private void doImportFromWeChat() {
        var json = FriendSync.read(this);
        if (json == null) {
            importFailed();
            return;
        }
        try {
            var array = new JSONObject(json).optJSONArray(FriendSync.KEY_FRIENDS);
            if (array == null || array.length() == 0) {
                importFailed();
                return;
            }
            // 保留已选的通话类型，避免镜像导入后需要重新设置
            var callTypes = new HashMap<String, String>();
            for (var friend : friends) {
                callTypes.put(friend.wxid, friend.callType);
            }
            // 镜像导入：先清空，再以微信数据为准重建
            friends.clear();
            for (var i = 0; i < array.length(); i++) {
                var item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                var username = item.optString(FriendSync.KEY_USERNAME, "");
                if (username.isEmpty()) {
                    continue;
                }
                var name = item.optString(FriendSync.KEY_NAME, username);
                if (name.isEmpty()) {
                    name = username;
                }
                var callType = callTypes.get(username);
                if (callType == null) {
                    callType = ShortcutHelp.CALL_VIDEO;
                }
                friends.add(new Friend(name, username, callType));
            }
            saveFriends();
            setImporting(false);
            refresh();
            showSuccess(getString(R.string.toast_import_done, friends.size()));
        } catch (Exception e) {
            LogHelp.e("XpWeChatCall", "import friends failed: " + e.getMessage(), e);
            importFailed();
        }
    }

    /**
     * 导入失败：没有本地好友时展示可重试错误页，已有好友则保留列表只做轻提示。
     */
    private void importFailed() {
        setImporting(false);
        if (friends.isEmpty()) {
            showError(getString(R.string.error_import_desc), this::importFromWeChat);
        } else {
            toast(R.string.toast_import_none);
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(CharSequence text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
