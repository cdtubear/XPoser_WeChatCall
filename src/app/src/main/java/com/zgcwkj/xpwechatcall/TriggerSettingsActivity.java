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
import com.zgcwkj.xpwechatcall.trigger.BtProtocol;
import com.zgcwkj.xpwechatcall.trigger.KeepAliveHelper;
import com.zgcwkj.xpwechatcall.trigger.RootShell;
import com.zgcwkj.xpwechatcall.trigger.TriggerApi;
import com.zgcwkj.xpwechatcall.trigger.TriggerService;
import com.zgcwkj.xpwechatcall.trigger.WatchdogHelper;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

/**
 * 外部触发设置页
 *
 * 管理 Token、监听地址、自动解锁、限流与结果回调。
 */
public class TriggerSettingsActivity extends Activity {

    private static final int REQ_PERMISSIONS = 2001;

    private Switch swEnable;
    private Switch swUnlock;
    private Switch swWatchdog;
    private Switch swBt;
    private Switch swBtBond;
    private TextView tvStatus;
    private TextView tvToken;
    private TextView tvHint;
    private TextView tvKeepAlive;
    private TextView tvBtStatus;
    private EditText etBind;
    private EditText etPort;
    private EditText etRate;
    private EditText etTimeout;
    private EditText etCallback;
    private EditText etPackages;

    /** 程序化更新 Switch 时避免触发保存逻辑 */
    private boolean syncingUi;
    private boolean keepAliveBusy;

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
        // 通知栏「停止」会直接关闭开关，回到页面时同步一次
        syncingUi = true;
        swEnable.setChecked(TriggerContract.isEnabled(this));
        swBt.setChecked(TriggerContract.isBtEnabled(this));
        swBtBond.setChecked(TriggerContract.btRequireBond(this));
        syncingUi = false;
        updateStatus();
        updateBtStatus();
        refreshKeepAliveAsync();
    }

    private void bindViews() {
        swEnable = findViewById(R.id.sw_enable);
        swUnlock = findViewById(R.id.sw_unlock);
        swWatchdog = findViewById(R.id.sw_watchdog);
        swBt = findViewById(R.id.sw_bt);
        swBtBond = findViewById(R.id.sw_bt_bond);
        tvStatus = findViewById(R.id.tv_status);
        tvToken = findViewById(R.id.tv_token);
        tvHint = findViewById(R.id.tv_hint);
        tvKeepAlive = findViewById(R.id.tv_keepalive);
        tvBtStatus = findViewById(R.id.tv_bt_status);
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
        swBt.setChecked(TriggerContract.isBtEnabled(this));
        swBtBond.setChecked(TriggerContract.btRequireBond(this));
        tvToken.setText(TriggerContract.token(this));
        etBind.setText(TriggerContract.bind(this));
        etPort.setText(String.valueOf(TriggerContract.port(this)));
        etRate.setText(String.valueOf(TriggerContract.rateLimit(this)));
        etTimeout.setText(String.valueOf(TriggerContract.timeoutSec(this)));
        etCallback.setText(TriggerContract.callbackHosts(this));
        etPackages.setText(TriggerContract.allowedPackages(this));
        updateStatus();
        updateBtStatus();
        updateHint();
    }

    private void bindActions() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_copy_token).setOnClickListener(v -> copyToken());
        findViewById(R.id.btn_reset_token).setOnClickListener(v -> confirmResetToken());
        findViewById(R.id.btn_save).setOnClickListener(v -> saveAndApply());
        findViewById(R.id.btn_test).setOnClickListener(v -> testStatus());
        findViewById(R.id.btn_root).setOnClickListener(v -> checkRoot());
        findViewById(R.id.btn_battery).setOnClickListener(v -> requestBatteryWhitelist());
        findViewById(R.id.btn_autostart).setOnClickListener(v ->
            KeepAliveHelper.openAutoStartSettings(this));
        findViewById(R.id.btn_bt_uuid).setOnClickListener(v -> copyBluetoothUuids());
        findViewById(R.id.btn_bt_help).setOnClickListener(v -> showBluetoothHelp());
        swEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!syncingUi) {
                saveAndApply();
            }
        });
        swBt.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!syncingUi) {
                saveAndApply();
            }
        });
        swBtBond.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!syncingUi) {
                saveAndApply();
            }
        });
        swWatchdog.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!syncingUi) {
                toggleWatchdog(isChecked);
            }
        });
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
        var btEnabled = swBt.isChecked();
        TriggerContract.prefs(this).edit()
            .putBoolean(TriggerContract.K_ENABLED, enabled)
            .putBoolean(TriggerContract.K_BT_ENABLED, btEnabled)
            .putBoolean(TriggerContract.K_BT_REQUIRE_BOND, swBtBond.isChecked())
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

        // 先停止旧服务，确保新地址/端口/蓝牙状态生效
        TriggerService.stop(this);
        if (enabled || btEnabled) {
            requestRuntimePermissions(btEnabled);
            if (btEnabled && hasBluetoothPermissions() && !isBluetoothOn()) {
                toast("请先打开系统蓝牙，开启后服务会自动广播");
            }
            ensureKeepAliveOnEnable();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    TriggerService.start(this);
                } catch (Throwable e) {
                    toast("启动服务失败: " + e.getMessage());
                }
            }, 500);
        }
        updateHint();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            updateStatus();
            updateBtStatus();
        }, 1200);
        toast("已保存");
    }

    /**
     * 开启外部触发时顺带申请保活：root 静默加白名单；看门狗按开关状态安装
     */
    private void ensureKeepAliveOnEnable() {
        final var wantWatchdog = swWatchdog.isChecked();
        new Thread(() -> {
            if (RootShell.hasRoot(this)) {
                KeepAliveHelper.applyBatteryWhitelistWithRoot(this);
                if (wantWatchdog && !WatchdogHelper.isInstalled(this)) {
                    WatchdogHelper.install(this);
                }
            }
            runOnUiThread(this::refreshKeepAliveAsync);
        }, "xp-keepalive-apply").start();
    }

    private void requestBatteryWhitelist() {
        if (KeepAliveHelper.isIgnoringBatteryOptimizations(this)) {
            toast("已在电池优化白名单");
            return;
        }
        setKeepAliveBusy(true);
        new Thread(() -> {
            var ok = KeepAliveHelper.applyBatteryWhitelistWithRoot(this);
            runOnUiThread(() -> {
                setKeepAliveBusy(false);
                if (ok) {
                    toast("已加入电池优化白名单");
                    refreshKeepAliveAsync();
                } else {
                    // 非 root 或命令失败时，回退到系统弹窗
                    KeepAliveHelper.requestIgnoreBatteryOptimizations(this);
                }
            });
        }, "xp-battery-whitelist").start();
    }

    private void toggleWatchdog(boolean enable) {
        setKeepAliveBusy(true);
        new Thread(() -> {
            String message;
            boolean success;
            if (enable) {
                if (!WatchdogHelper.isSupported(this)) {
                    success = false;
                    message = "需要 root，以及 Magisk / KernelSU / APatch 的 /data/adb";
                } else {
                    // 后台启动前台服务需要绕过 Android 12+ 限制，先加入电池白名单
                    KeepAliveHelper.applyBatteryWhitelistWithRoot(this);
                    success = WatchdogHelper.install(this);
                    message = success ? "root 看门狗已安装" : "看门狗安装失败，请检查 root 授权";
                }
            } else {
                success = WatchdogHelper.uninstall(this);
                message = success ? "root 看门狗已移除" : "看门狗移除失败";
            }
            final var ok = success;
            runOnUiThread(() -> {
                setKeepAliveBusy(false);
                toast(message);
                if (!ok) {
                    syncingUi = true;
                    swWatchdog.setChecked(!enable);
                    syncingUi = false;
                }
                refreshKeepAliveAsync();
            });
        }, "xp-watchdog-toggle").start();
    }

    private void setKeepAliveBusy(boolean busy) {
        keepAliveBusy = busy;
        swWatchdog.setEnabled(!busy);
        findViewById(R.id.btn_battery).setEnabled(!busy);
        findViewById(R.id.btn_autostart).setEnabled(!busy);
    }

    /**
     * 后台读取 root / 看门狗 / 电池白名单状态，避免主线程执行 su
     */
    private void refreshKeepAliveAsync() {
        new Thread(() -> {
            var root = RootShell.hasRoot(this);
            var supported = root && WatchdogHelper.isSupported(this);
            var installed = supported && WatchdogHelper.isInstalled(this);
            var running = installed && WatchdogHelper.isRunning(this);
            var battery = KeepAliveHelper.isIgnoringBatteryOptimizations(this);
            runOnUiThread(() -> {
                syncingUi = true;
                swWatchdog.setChecked(installed);
                syncingUi = false;
                swWatchdog.setEnabled(!keepAliveBusy);

                String watchdog;
                if (!root) {
                    watchdog = "需要 root";
                } else if (!supported) {
                    watchdog = "不支持 /data/adb";
                } else if (!installed) {
                    watchdog = "未安装";
                } else {
                    watchdog = running ? "运行中" : "已安装，等待下次检查";
                }
                tvKeepAlive.setText("root: " + (root ? "已授权" : "未授权")
                    + "\n看门狗: " + watchdog
                    + "\n电池白名单: " + (battery ? "已加入" : "未加入"));
                updateStatus();
            });
        }, "xp-keepalive-status").start();
    }

    /**
     * 一次性申请前台服务所需的运行时权限
     *
     * 通知权限与蓝牙权限分开请求时，第二次请求可能在第一次弹窗期间被系统忽略，
     * 因此合并成一次 requestPermissions。
     */
    private void requestRuntimePermissions(boolean includeBluetooth) {
        var missing = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (includeBluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    private boolean isBluetoothOn() {
        try {
            var manager = (android.bluetooth.BluetoothManager)
                getSystemService(Context.BLUETOOTH_SERVICE);
            var adapter = manager == null ? null : manager.getAdapter();
            return adapter != null && adapter.isEnabled();
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) {
            return;
        }
        // 通知权限被拒不影响服务运行；这里只关心蓝牙权限是否满足
        if (TriggerContract.isBtEnabled(this) && !hasBluetoothPermissions()) {
            toast("未授予蓝牙权限，蓝牙触发无法启动");
            syncingUi = true;
            swBt.setChecked(false);
            syncingUi = false;
            TriggerContract.setBtEnabled(this, false);
            if (!TriggerContract.isEnabled(this)) {
                TriggerService.stop(this);
            }
        } else if (TriggerContract.isEnabled(this) || TriggerContract.isBtEnabled(this)) {
            if (TriggerContract.isBtEnabled(this) && !isBluetoothOn()) {
                toast("蓝牙未打开，打开后服务会自动广播");
            }
            try {
                TriggerService.start(this);
            } catch (Throwable e) {
                toast("启动服务失败: " + e.getMessage());
            }
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            updateStatus();
            updateBtStatus();
        }, 1200);
    }

    private void copyBluetoothUuids() {
        var text = "Service: " + BtProtocol.SERVICE_UUID
            + "\nRX (write): " + BtProtocol.RX_UUID
            + "\nTX (notify): " + BtProtocol.TX_UUID;
        var manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("XpWeChatCall BLE UUID", text));
            toast("BLE UUID 已复制");
        }
    }

    private void showBluetoothHelp() {
        var text = "Service\n" + BtProtocol.SERVICE_UUID
            + "\n\nRX（设备写入）\n" + BtProtocol.RX_UUID
            + "\n\nTX（设备订阅 Notify）\n" + BtProtocol.TX_UUID
            + "\n\n指令为 UTF-8 JSON，以 \\n 结尾，单帧不超过 1024 字节：\n"
            + "{\"token\":\"<TOKEN>\",\"action\":\"call\","
            + "\"wxid\":\"wxid_abc123\",\"type\":\"video\"}\n\n"
            + "可用 action：call / query / health / friends / resolve / ping / help\n\n"
            + "响应直接通过 TX Notify 返回；超过 MTU 时自动分片，"
            + "格式为 @序号/总数:内容，按序号拼接即可。\n\n"
            + "通话结束后会额外收到终态：\n"
            + "{\"event\":\"result\",\"ok\":true,\"data\":{...}}\n\n"
            + "完整文档见仓库 docs/bluetooth.md。";
        showDialog("蓝牙触发协议", text);
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
        var http = TriggerContract.isEnabled(this);
        var bt = TriggerContract.isBtEnabled(this);
        if (!http && !bt) {
            tvStatus.setText("已停止");
            return;
        }
        var text = new StringBuilder();
        if (http) {
            text.append(TriggerService.isHttpRunning()
                ? "HTTP 运行中 · " + TriggerContract.port(this)
                : "HTTP 启动中或失败");
        }
        if (bt) {
            if (text.length() > 0) {
                text.append(" · ");
            }
            text.append("BLE ");
            if (TriggerService.isBluetoothAdvertising()) {
                text.append("广播中");
            } else if (TriggerService.isBluetoothRunning()) {
                text.append("已启动");
            } else {
                text.append("启动中或失败");
            }
        }
        tvStatus.setText(text.toString());
    }

    private void updateBtStatus() {
        if (!TriggerContract.isBtEnabled(this)) {
            tvBtStatus.setText("未启用");
            return;
        }
        var text = new StringBuilder();
        text.append("服务: ").append(TriggerService.isBluetoothRunning() ? "已启动" : "未启动");
        text.append(" · 广播: ").append(TriggerService.isBluetoothAdvertising() ? "是" : "否");
        text.append(" · 连接: ").append(TriggerService.bluetoothConnectedCount());
        var lastCommand = TriggerService.bluetoothLastCommand();
        if (!lastCommand.isEmpty()) {
            text.append("\n最近指令: ").append(lastCommand);
        }
        var lastResult = TriggerService.bluetoothLastResult();
        if (!lastResult.isEmpty()) {
            text.append("\n最近结果: ").append(lastResult);
        }
        tvBtStatus.setText(text.toString());
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
            + "好友列表\n"
            + "curl -H \"Authorization: Bearer <TOKEN>\" \\\n  " + base
            + "/v1/friends?limit=50\n\n"
            + "发起通话\n"
            + "curl -X POST " + base + "/v1/call \\\n"
            + "  -H \"Authorization: Bearer <TOKEN>\" \\\n"
            + "  -H \"Content-Type: application/json\" \\\n"
            + "  -d '{\"name\":\"张三\",\"type\":\"video\"}'\n\n"
            + "查询结果\n"
            + "curl -H \"Authorization: Bearer <TOKEN>\" \\\n  " + base
            + "/v1/result/{request_id}\n\n"
            + "蓝牙触发（BLE）\n"
            + "Service: " + BtProtocol.SERVICE_UUID + "\n"
            + "RX: " + BtProtocol.RX_UUID + "\n"
            + "TX: " + BtProtocol.TX_UUID + "\n"
            + "{\"token\":\"<TOKEN>\",\"action\":\"call\",\"name\":\"张三\",\"type\":\"video\"}\n\n"
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
