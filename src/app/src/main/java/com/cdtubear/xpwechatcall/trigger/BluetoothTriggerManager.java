package com.cdtubear.xpwechatcall.trigger;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.TriggerContract;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 蓝牙触发管理器
 *
 * 手机作为 BLE 外设（GATT Server）广播 Nordic UART Service。外部设备
 * 连接后向 RX 特征写入 UTF-8 JSON 指令，手机通过 TX 特征 Notify 返回
 * 响应和终态结果。指令最终交给 TriggerApi，与 HTTP / Provider 共用同一套
 * 好友解析、忙状态、幂等和超时逻辑。
 */
@SuppressLint("MissingPermission")
public final class BluetoothTriggerManager {

    private static final String TAG = "XpWeChatCall";

    /** 状态变化回调，供前台服务刷新通知 */
    public interface StatusListener {
        void onStatusChanged();
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    /** 每个连接的指令缓冲，按设备地址隔离 */
    private final Map<String, StringBuilder> buffers = new ConcurrentHashMap<>();
    /** 当前连接的设备 */
    private final Map<String, BluetoothDevice> connected = new ConcurrentHashMap<>();
    /** 已订阅 TX Notify 的设备地址 */
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    /** request_id -> 设备地址，用于把终态结果发回发起方 */
    private final Map<String, String> requestDevices = new ConcurrentHashMap<>();
    /** 已发送过终态的设备请求，避免重复状态回报导致重复通知 */
    private final Set<String> terminalSent = ConcurrentHashMap.newKeySet();
    /** 每个连接的 MTU，默认 23 */
    private final Map<String, Integer> mtuMap = new ConcurrentHashMap<>();

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic txCharacteristic;

    private volatile boolean running;
    private volatile boolean advertising;
    private volatile boolean receiverRegistered;
    private volatile StatusListener statusListener;
    private volatile String lastCommand = "";
    private volatile String lastResult = "";

    public BluetoothTriggerManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isAdvertising() {
        return advertising;
    }

    public int connectedCount() {
        return connected.size();
    }

    public String lastCommand() {
        return lastCommand;
    }

    public String lastResult() {
        return lastResult;
    }

    /**
     * 启动 GATT Server 和广播
     *
     * @return 是否成功启动（蓝牙关闭、无权限或不支持 BLE 时返回 false）
     */
    public synchronized boolean start() {
        if (running) {
            return true;
        }
        registerAdapterReceiver();
        bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            LogHelp.w(TAG, "bluetooth manager unavailable");
            return false;
        }
        adapter = bluetoothManager.getAdapter();
        if (adapter == null) {
            LogHelp.w(TAG, "bluetooth adapter unavailable");
            return false;
        }
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            LogHelp.w(TAG, "BLE not supported");
            return false;
        }
        if (!hasPermissions()) {
            LogHelp.w(TAG, "bluetooth permissions not granted");
            return false;
        }
        if (!adapter.isEnabled()) {
            LogHelp.w(TAG, "bluetooth is disabled");
            return false;
        }

        try {
            server = bluetoothManager.openGattServer(context, gattCallback);
            if (server == null) {
                LogHelp.w(TAG, "openGattServer returned null");
                return false;
            }
            if (!server.addService(buildService())) {
                LogHelp.w(TAG, "addService failed");
                closeServer();
                return false;
            }
            running = true;
            startAdvertising();
            notifyStatus();
            LogHelp.i(TAG, "bluetooth trigger started");
            return true;
        } catch (Throwable e) {
            LogHelp.e(TAG, "bluetooth start failed: " + e.getMessage(), e);
            closeServer();
            running = false;
            notifyStatus();
            return false;
        }
    }

    /**
     * 停止 GATT Server 和广播
     */
    public synchronized void stop() {
        stopInternal(true);
    }

    /**
     * 服务销毁时调用：停止并释放工作线程
     */
    public synchronized void destroy() {
        stopInternal(true);
        worker.shutdownNow();
    }

    private synchronized void stopInternal(boolean unregister) {
        running = false;
        advertising = false;
        stopAdvertising();
        closeServer();
        buffers.clear();
        connected.clear();
        subscribed.clear();
        requestDevices.clear();
        terminalSent.clear();
        mtuMap.clear();
        txCharacteristic = null;
        if (unregister) {
            unregisterAdapterReceiver();
        }
        notifyStatus();
        LogHelp.i(TAG, "bluetooth trigger stopped");
    }

    /**
     * 服务收到终态时调用，把结果 Notify 给发起请求的设备
     */
    public void notifyTerminal(JSONObject record) {
        if (record == null) {
            return;
        }
        var requestId = record.optString("request_id", "");
        var state = record.optString("state", "");
        if (!terminalSent.add(requestId)) {
            return;
        }
        lastResult = requestId + " " + state;
        var address = requestDevices.remove(requestId);
        var event = BtProtocol.resultEvent(record);
        if (address != null && !address.isEmpty()) {
            var device = connected.get(address);
            if (device != null) {
                send(device, event);
            }
        } else if (BtProtocol.SOURCE_BT.equals(record.optString("source", ""))) {
            // 服务重启导致映射丢失时，仅对蓝牙发起的请求广播，避免把 HTTP 结果泄露给 BLE 设备
            for (var device : connected.values()) {
                send(device, event);
            }
        }
        notifyStatus();
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
            && context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothGattService buildService() {
        var service = new BluetoothGattService(BtProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY);
        var rx = new BluetoothGattCharacteristic(BtProtocol.RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE
                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE);
        var tx = new BluetoothGattCharacteristic(BtProtocol.TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ);
        tx.addDescriptor(new BluetoothGattDescriptor(BtProtocol.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        service.addCharacteristic(rx);
        service.addCharacteristic(tx);
        txCharacteristic = tx;
        return service;
    }

    private void startAdvertising() {
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                LogHelp.w(TAG, "BLE advertiser unavailable");
                return;
            }
            var settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build();
            var data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(BtProtocol.SERVICE_UUID))
                .setIncludeDeviceName(false)
                .build();
            var scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
        } catch (Throwable e) {
            LogHelp.e(TAG, "startAdvertising failed: " + e.getMessage(), e);
        }
    }

    private void stopAdvertising() {
        try {
            if (advertiser != null) {
                advertiser.stopAdvertising(advertiseCallback);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        advertiser = null;
    }

    private void closeServer() {
        try {
            if (server != null) {
                server.clearServices();
                server.close();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        server = null;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            notifyStatus();
            LogHelp.i(TAG, "BLE advertising started");
        }

        @Override
        public void onStartFailure(int errorCode) {
            advertising = false;
            notifyStatus();
            LogHelp.w(TAG, "BLE advertising failed: " + errorCode);
        }
    };

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            var address = BtProtocol.deviceAddress(device);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.put(address, device);
                LogHelp.i(TAG, "BLE connected: " + BtProtocol.deviceLabel(device));
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected.remove(address);
                buffers.remove(address);
                subscribed.remove(address);
                mtuMap.remove(address);
                LogHelp.i(TAG, "BLE disconnected: " + BtProtocol.deviceLabel(device));
            }
            notifyStatus();
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            var success = false;
            if (BtProtocol.RX_UUID.equals(characteristic.getUuid())) {
                success = handleWrite(device, value);
            }
            if (responseNeeded && server != null) {
                server.sendResponse(device, requestId,
                    success ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE,
                    offset, value);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (BtProtocol.CCCD_UUID.equals(descriptor.getUuid())) {
                var address = BtProtocol.deviceAddress(device);
                if (value != null && value.length > 0
                    && (value[0] == 0x01 || value[0] == 0x02)) {
                    subscribed.add(address);
                } else {
                    subscribed.remove(address);
                }
            }
            if (responseNeeded && server != null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            mtuMap.put(BtProtocol.deviceAddress(device), mtu);
        }
    };

    /**
     * 处理一次 RX 写入，支持换行分隔和单次完整 JSON
     */
    private boolean handleWrite(BluetoothDevice device, byte[] value) {
        if (value == null || value.length == 0) {
            return false;
        }
        var address = BtProtocol.deviceAddress(device);
        var buffer = buffers.computeIfAbsent(address, key -> new StringBuilder());
        buffer.append(new String(value, StandardCharsets.UTF_8));
        if (buffer.toString().getBytes(StandardCharsets.UTF_8).length > BtProtocol.MAX_FRAME) {
            buffer.setLength(0);
            sendError(device, TriggerContract.E_BAD_REQUEST, "frame too large");
            return false;
        }

        // 先处理所有完整换行帧
        var newline = buffer.indexOf("\n");
        while (newline >= 0) {
            var line = buffer.substring(0, newline);
            buffer.delete(0, newline + 1);
            if (!line.trim().isEmpty()) {
                worker.execute(() -> process(device, line));
            }
            newline = buffer.indexOf("\n");
        }

        // 没有换行时，尝试把当前缓冲当作完整 JSON（方便 nRF Connect 单次写入）
        if (buffer.length() > 0) {
            var text = buffer.toString().trim();
            if (text.startsWith("{") && text.endsWith("}") && BtProtocol.parse(text) != null) {
                buffer.setLength(0);
                worker.execute(() -> process(device, text));
            }
        }
        return true;
    }

    private void process(BluetoothDevice device, String line) {
        var request = BtProtocol.parse(line);
        if (request == null) {
            sendError(device, TriggerContract.E_BAD_REQUEST, "invalid json");
            return;
        }
        if (TriggerContract.btRequireBond(context) && !isBonded(device)) {
            sendError(device, TriggerContract.E_BT_NOT_BONDED,
                "device is not bonded, pair it in system bluetooth settings first");
            return;
        }
        if (!BtProtocol.authorized(context, request)) {
            sendError(device, TriggerContract.E_UNAUTHORIZED, "invalid token");
            return;
        }

        var action = request.optString("action", BtProtocol.ACTION_CALL).trim();
        lastCommand = action + " " + BtProtocol.deviceLabel(device);
        var response = BtCommandHandler.handle(context, request);
        var requestId = response.optString("request_id", "");
        if (BtProtocol.ACTION_CALL.equalsIgnoreCase(action)
            && !requestId.isEmpty() && response.optBoolean("ok", false)) {
            requestDevices.put(requestId, BtProtocol.deviceAddress(device));
        }
        send(device, response);
        notifyStatus();
    }

    private boolean isBonded(BluetoothDevice device) {
        try {
            return device.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (Throwable e) {
            return false;
        }
    }

    private void sendError(BluetoothDevice device, String code, String message) {
        send(device, BtProtocol.error(code, message));
    }

    /**
     * 发送响应；超过当前 MTU 时自动分片，格式为 @序号/总数:内容
     */
    private void send(BluetoothDevice device, JSONObject json) {
        if (device == null || json == null) {
            return;
        }
        var address = BtProtocol.deviceAddress(device);
        if (!subscribed.contains(address)) {
            LogHelp.w(TAG, "BLE notify skipped, TX not subscribed: "
                + BtProtocol.deviceLabel(device));
            return;
        }
        var tx = txCharacteristic;
        var gattServer = server;
        if (tx == null || gattServer == null) {
            return;
        }
        var payload = json.toString();
        var mtu = mtuMap.getOrDefault(address, 23);
        var max = Math.max(20, mtu - 3);
        List<String> chunks;
        if (payload.getBytes(StandardCharsets.UTF_8).length <= max) {
            chunks = new ArrayList<>();
            chunks.add(payload);
        } else {
            // 预留 @序号/总数: 前缀空间，避免分片后超过 MTU
            chunks = split(payload, Math.max(1, max - 12));
        }
        for (var i = 0; i < chunks.size(); i++) {
            var prefix = chunks.size() == 1 ? ""
                : "@" + (i + 1) + "/" + chunks.size() + ":";
            var data = (prefix + chunks.get(i)).getBytes(StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gattServer.notifyCharacteristicChanged(device, tx, false, data);
                } else {
                    tx.setValue(data);
                    gattServer.notifyCharacteristicChanged(device, tx, false);
                }
            } catch (Throwable e) {
                LogHelp.w(TAG, "BLE notify failed: " + e.getMessage());
                return;
            }
        }
    }

    /**
     * 按 UTF-8 字节数分片，避免截断多字节字符
     */
    private static List<String> split(String text, int maxBytes) {
        var result = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            result.add("");
            return result;
        }
        var current = new StringBuilder();
        var currentBytes = 0;
        for (var i = 0; i < text.length(); ) {
            var codePoint = text.codePointAt(i);
            var piece = new String(Character.toChars(codePoint));
            var size = piece.getBytes(StandardCharsets.UTF_8).length;
            if (currentBytes + size > maxBytes && current.length() > 0) {
                result.add(current.toString());
                current.setLength(0);
                currentBytes = 0;
            }
            current.append(piece);
            currentBytes += size;
            i += Character.charCount(codePoint);
        }
        if (current.length() > 0 || result.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private void registerAdapterReceiver() {
        if (receiverRegistered) {
            return;
        }
        try {
            var filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(adapterReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(adapterReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Throwable e) {
            LogHelp.w(TAG, "register bluetooth receiver failed: " + e.getMessage());
        }
    }

    private void unregisterAdapterReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(adapterReceiver);
        } catch (Throwable ignored) {
            // 忽略
        }
        receiverRegistered = false;
    }

    private final BroadcastReceiver adapterReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            var state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                handler.post(() -> {
                    if (!running) {
                        start();
                    }
                });
            } else if (state == BluetoothAdapter.STATE_OFF) {
                handler.post(() -> stopInternal(false));
            }
        }
    };

    private void notifyStatus() {
        var listener = statusListener;
        if (listener == null) {
            return;
        }
        handler.post(listener::onStatusChanged);
    }
}
