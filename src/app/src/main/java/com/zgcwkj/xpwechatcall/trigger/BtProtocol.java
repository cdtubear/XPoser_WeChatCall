package com.zgcwkj.xpwechatcall.trigger;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

import com.zgcwkj.comm.TriggerContract;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * 蓝牙触发协议常量与工具
 *
 * 使用 Nordic UART Service 兼容 UUID，设备端可以直接使用 ESP32 / Arduino
 * 常见的 BLE UART 示例。指令为 UTF-8 JSON，使用换行分隔。
 */
public final class BtProtocol {

    /** Nordic UART Service */
    public static final UUID SERVICE_UUID =
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    /** 设备写入指令 */
    public static final UUID RX_UUID =
        UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    /** 手机 Notify 响应与结果 */
    public static final UUID TX_UUID =
        UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    /** 标准 CCCD */
    public static final UUID CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** 单条指令最大字节数，超过后丢弃当前缓冲 */
    public static final int MAX_FRAME = 1024;

    /** 请求来源，写入 TriggerStore，用于决定终态是否回传 BLE */
    public static final String SOURCE_BT = "bt";
    public static final String SOURCE_HTTP = "http";

    /** 指令动作 */
    public static final String ACTION_CALL = "call";
    public static final String ACTION_QUERY = "query";
    public static final String ACTION_HEALTH = "health";
    public static final String ACTION_FRIENDS = "friends";
    public static final String ACTION_RESOLVE = "resolve";
    public static final String ACTION_PING = "ping";
    public static final String ACTION_HELP = "help";

    private BtProtocol() {
    }

    /**
     * 解析一条 JSON 指令，失败返回 null
     */
    public static JSONObject parse(String text) {
        if (text == null) {
            return null;
        }
        var value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验指令中的 Token
     */
    public static boolean authorized(Context context, JSONObject request) {
        if (request == null) {
            return false;
        }
        return safeEquals(TriggerContract.token(context), request.optString("token", ""));
    }

    /**
     * 常量时间比较，避免 Token 通过响应时间被猜测
     */
    public static boolean safeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        var a = expected.getBytes(StandardCharsets.UTF_8);
        var b = provided.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        var result = 0;
        for (var i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * 生成请求标识，格式与 HTTP 接口保持一致
     */
    public static String newRequestId() {
        var random = new SecureRandom();
        return "bt_" + System.currentTimeMillis() + "_"
            + Integer.toHexString(random.nextInt() & 0xFFFFFF);
    }

    /**
     * 设备展示名：优先名称，取不到时用地址后 4 位
     */
    @SuppressLint("MissingPermission")
    public static String deviceLabel(BluetoothDevice device) {
        if (device == null) {
            return "unknown";
        }
        try {
            var name = device.getName();
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
        } catch (Throwable ignored) {
            // 权限不足或设备已断开
        }
        try {
            var address = device.getAddress();
            if (address != null && address.length() >= 4) {
                return "..." + address.substring(address.length() - 4);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return "unknown";
    }

    /**
     * 设备地址，取不到时返回空串
     */
    public static String deviceAddress(BluetoothDevice device) {
        if (device == null) {
            return "";
        }
        try {
            var address = device.getAddress();
            return address == null ? "" : address;
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 构造错误响应
     */
    public static JSONObject error(String code, String message) {
        var json = new JSONObject();
        try {
            json.put("ok", false);
            json.put("error", code);
            json.put("message", message == null ? "" : message);
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }

    /**
     * 构造成功响应
     */
    public static JSONObject ok() {
        var json = new JSONObject();
        try {
            json.put("ok", true);
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }

    /**
     * 终态通知
     */
    public static JSONObject resultEvent(JSONObject record) {
        var json = new JSONObject();
        try {
            json.put("event", "result");
            json.put("ok", true);
            json.put("data", record == null ? new JSONObject() : record);
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }
}
