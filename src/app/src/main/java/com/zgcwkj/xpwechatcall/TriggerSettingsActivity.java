package com.zgcwkj.xpwechatcall;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.zgcwkj.comm.ConfigHelp;
import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.TriggerContract;
import com.zgcwkj.xpwechatcall.trigger.RootShell;
import com.zgcwkj.xpwechatcall.trigger.TriggerApi;
import com.zgcwkj.xpwechatcall.trigger.TriggerService;

import org.json.JSONObject;

import java.io.File;

/**
 * 外部触发设置页
 *
 * 管理 Token、监听地址、自动解锁、限流与结果回调。
 */
public class TriggerSettingsActivity extends Activity {

    private static final int REQ_NOTIFICATION = 2001;

    private Switch swEnable;
    private Switch swUnlock;
    private TextView tvStatus;
    private TextView tvToken;
    private TextView tvHint;
    private EditText etBind;
    private EditText etPort;
    private EditText etRate;
    private EditText etTimeout;
    private EditText etCallback;
    private EditText etPackages;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogHelp.init(new File(getFilesDir(), "logs"), ConfigHelp.isLogEnabled(this));
        setContentView(R.layout.activity_trigger);

        bindViews();
        applyInsets();
        loadSettings();
        bindActions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void bindViews() {
        swEnable = findViewById(R.id.sw_enable);
        swUnlock = findViewById(R.id.sw_unlock);
        tvStatus = findViewById(R.id.tv_status);
        tvToken = findViewById(R.id.tv_token);
        tvHint = findViewById(R.id.tv_hint);
        etBind = findViewById(R.id.et_bind);
        etPort = findViewById(R.id.et_port);
        etRate = findViewById(R.id.et_rate);
        etTimeout = findViewById(R.id.et_timeout);
        etCallback = findViewById(R.id.et_callback);
        etPackages = findViewById(R.id.et_packages);
    }

    /**
     * targetSdk 36 强制 edge-to-edge，标题栏延伸到状态栏下方
     */
    private void applyInsets() {
        var root = findViewById(R.id.root);
        var titleBar = findViewById(R.id.title_bar);
        var scroll = findViewById(R.id.scroll);
        var titleTop = titleBar.getPaddingTop();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, 0, bars.right, 0);
            titleBar.setPadding(titleBar.getPaddingLeft(), titleTop + bars.top,
                titleBar.getPaddingRight(), titleBar.getPaddingBottom());
            scroll.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });
    }

    private void loadSettings() {
        swEnable.setChecked(TriggerContract.isEnabled(this));
        swUnlock.setChecked(TriggerContract.autoUnlock(this));
        tvToken.setText(TriggerContract.token(this));
        etBind.setText(TriggerContract.bind(this));
        etPort.setText(String.valueOf(TriggerContract.port(this)));
        etRate.setText(String.valueOf(TriggerContract.rateLimit(this)));
        etTimeout.setText(String.valueOf(TriggerContract.timeoutSec(this)));
        etCallback.setText(TriggerContract.callbackHosts(this));
        etPackages.setText(TriggerContract.allowedPackages(this));
        updateStatus();
        updateHint();
    }

    private void bindActions() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_copy_token).setOnClickListener(v -> copyToken());
        findViewById(R.id.btn_reset_token).setOnClickListener(v -> confirmResetToken());
        findViewById(R.id.btn_save).setOnClickListener(v -> saveAndApply());
        findViewById(R.id.btn_test).setOnClickListener(v -> testStatus());
        findViewById(R.id.btn_root).setOnClickListener(v -> checkRoot());
        swEnable.setOnCheckedChangeListener((buttonView, isChecked) -> saveAndApply());
    }

    private void copyToken() {
        var manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("XpWeChatCall Token",
                TriggerContract.token(this)));
            toast("Token 已复制");
        }
    }

    private void confirmResetToken() {
        new AlertDialog.Builder(this)
            .setTitle("重置 Token")
            .setMessage("重置后所有旧调用方会立即失效，需要更新 webhook 配置。")
            .setPositiveButton("重置", (dialog, which) -> {
                TriggerContract.prefs(this).edit()
                    .putString(TriggerContract.K_TOKEN, TriggerContract.newToken())
                    .commit();
                tvToken.setText(TriggerContract.token(this));
                toast("Token 已重置");
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 保存配置并按需重启服务
     */
    private void saveAndApply() {
        int port;
        int rate;
        int timeout;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
            rate = Integer.parseInt(etRate.getText().toString().trim());
            timeout = Integer.parseInt(etTimeout.getText().toString().trim());
        } catch (Exception e) {
            toast("端口、限流、超时必须是数字");
            return;
        }
        if (port < 1024 || port > 65535) {
            toast("端口范围 1024-65535");
            return;
        }
        var bind = etBind.getText().toString().trim();
        if (bind.isEmpty()) {
            bind = TriggerContract.DEF_BIND;
            etBind.setText(bind);
        }
        rate = Math.max(0, Math.min(rate, 1000));
        timeout = Math.max(5, Math.min(timeout, 120));
        etRate.setText(String.valueOf(rate));
        etTimeout.setText(String.valueOf(timeout));

        var enabled = swEnable.isChecked();
        TriggerContract.prefs(this).edit()
            .putBoolean(TriggerContract.K_ENABLED, enabled)
            .putString(TriggerContract.K_BIND, bind)
            .putInt(TriggerContract.K_PORT, port)
            .putBoolean(TriggerContract.K_AUTO_UNLOCK, swUnlock.isChecked())
            .putInt(TriggerContract.K_RATE_LIMIT, rate)
            .putInt(TriggerContract.K_TIMEOUT, timeout)
            .putString(TriggerContract.K_CALLBACK_HOSTS,
                etCallback.getText().toString().trim())
            .putString(TriggerContract.K_ALLOWED_PACKAGES,
                etPackages.getText().toString().trim())
            .commit();

        // 先停止旧服务，确保新地址/端口生效
        TriggerService.stop(this);
        if (enabled) {
            requestNotificationPermission();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    TriggerService.start(this);
                } catch (Throwable e) {
                    toast("启动服务失败: " + e.getMessage());
                }
            }, 500);
        }
        updateHint();
        new Handler(Looper.getMainLooper()).postDelayed(this::updateStatus, 1200);
        toast("已保存");
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQ_NOTIFICATION);
        }
    }

    private void testStatus() {
        new Thread(() -> {
            var json = TriggerApi.health(this);
            var text = pretty(json);
            runOnUiThread(() -> showDialog("服务状态", text));
        }, "xp-trigger-health").start();
    }

    private void checkRoot() {
        new Thread(() -> {
            var granted = RootShell.checkRoot(this);
            runOnUiThread(() -> {
                toast(granted ? "root 已授权" : "root 未授权或授权被拒绝");
                testStatus();
            });
        }, "xp-trigger-root").start();
    }

    private void updateStatus() {
        if (!TriggerContract.isEnabled(this)) {
            tvStatus.setText("已停止");
            return;
        }
        tvStatus.setText(TriggerService.isRunning()
            ? "运行中 · " + TriggerContract.port(this)
            : "启动中或失败");
    }

    private void updateHint() {
        var host = TriggerContract.bind(this);
        if ("0.0.0.0".equals(host) || "::".equals(host)) {
            host = "手机IP";
        }
        var port = etPort.getText().toString().trim();
        if (port.isEmpty()) {
            port = String.valueOf(TriggerContract.DEF_PORT);
        }
        var base = "http://" + host + ":" + port;
        tvHint.setText("健康检查\n"
            + "curl -H \"Authorization: Bearer <TOKEN>\" \\\n  " + base + "/v1/health\n\n"
            + "按昵称查 wxid\n"
            + "curl -X POST " + base + "/v1/resolve \\\n"
            + "  -H \"Authorization: Bearer <TOKEN>\" \\\n"
            + "  -H \"Content-Type: application/json\" \\\n"
            + "  -d '{\"name\":\"张三\"}'\n\n"
            + "发起通话\n"
            + "curl -X POST " + base + "/v1/call \\\n"
            + "  -H \"Authorization: Bearer <TOKEN>\" \\\n"
            + "  -H \"Content-Type: application/json\" \\\n"
            + "  -d '{\"name\":\"张三\",\"type\":\"video\"}'\n\n"
            + "查询结果\n"
            + "curl -H \"Authorization: Bearer <TOKEN>\" \\\n  " + base
            + "/v1/result/{request_id}\n\n"
            + "注意：手机必须已解锁（或仅滑动锁），微信必须已登录。\n"
            + "SUCCESS 表示本机已触发通话界面，不代表对方已接听。");
    }

    private static String pretty(JSONObject json) {
        try {
            return json.toString(2);
        } catch (Exception e) {
            return json.toString();
        }
    }

    private void showDialog(String title, String message) {
        var view = new TextView(this);
        var padding = (int) (16 * getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding, padding, padding);
        view.setText(message);
        view.setTextSize(11);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        var scroll = new android.widget.ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
