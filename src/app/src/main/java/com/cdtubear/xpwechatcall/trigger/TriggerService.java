package com.cdtubear.xpwechatcall.trigger;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.graphics.drawable.Icon;

import com.zgcwkj.comm.ConfigHelp;
import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.TriggerContract;
import com.cdtubear.xpwechatcall.R;
import com.cdtubear.xpwechatcall.TriggerSettingsActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 外部触发 HTTP 服务
 *
 * 只在用户开启后运行，使用前台服务保证 webhook 随时可达。
 * 仅提供四个接口：健康检查、昵称解析、发起通话、查询结果。
 */
public class TriggerService extends Service {

    public static final String ACTION_START = "com.cdtubear.xpwechatcall.action.TRIGGER_START";
    public static final String ACTION_STOP = "com.cdtubear.xpwechatcall.action.TRIGGER_STOP";

    private static final String TAG = "XpWeChatCall";
    private static final String CHANNEL = "trigger_service";
    private static final int NOTIFICATION_ID = 0x5850;
    private static final int RESTART_REQUEST_CODE = 0x5851;
    private static final long RESTART_DELAY_MS = 3000L;
    private static final int MAX_HEADER = 8192;
    private static final int MAX_BODY = 64 * 1024;
    private static final String HEARTBEAT_FILE = "service_heartbeat";
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;

    private static volatile boolean sRunning;
    private static volatile boolean sHttpRunning;
    private static volatile BluetoothTriggerManager sBluetooth;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Deque<Long>> rateBuckets = new ConcurrentHashMap<>();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService requestPool;
    private long lastHeartbeat;

    public static boolean isRunning() {
        return sRunning;
    }

    public static boolean isHttpRunning() {
        return sHttpRunning;
    }

    public static boolean isBluetoothRunning() {
        var bluetooth = sBluetooth;
        return bluetooth != null && bluetooth.isRunning();
    }

    public static boolean isBluetoothAdvertising() {
        var bluetooth = sBluetooth;
        return bluetooth != null && bluetooth.isAdvertising();
    }

    public static int bluetoothConnectedCount() {
        var bluetooth = sBluetooth;
        return bluetooth == null ? 0 : bluetooth.connectedCount();
    }

    public static JSONArray bluetoothDevices() {
        var bluetooth = sBluetooth;
        return bluetooth == null ? new JSONArray() : bluetooth.deviceDetails();
    }

    public static String bluetoothLastCommand() {
        var bluetooth = sBluetooth;
        return bluetooth == null ? "" : bluetooth.lastCommand();
    }

    public static String bluetoothLastResult() {
        var bluetooth = sBluetooth;
        return bluetooth == null ? "" : bluetooth.lastResult();
    }

    public static void start(Context context) {
        var intent = new Intent(context, TriggerService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, TriggerService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LogHelp.init(new File(getFilesDir(), "logs"), ConfigHelp.isLogEnabled(this));
        createChannel();
        requestPool = Executors.newFixedThreadPool(4);
        sBluetooth = new BluetoothTriggerManager(this);
        sBluetooth.setStatusListener(() -> handler.post(this::refreshNotification));
        TriggerApi.setListener((context, record) -> {
            var bluetooth = sBluetooth;
            if (bluetooth != null) {
                bluetooth.notifyTerminal(record);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // 通知栏「停止」是用户的明确意图：同时关闭开关，避免看门狗又把服务拉起
            TriggerContract.setEnabled(this, false);
            TriggerContract.setBtEnabled(this, false);
            cancelRestart(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        cancelRestart(this);
        if (!TriggerContract.isEnabled(this) && !TriggerContract.isBtEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        sRunning = true;
        writeHeartbeat();
        if (TriggerContract.isEnabled(this)) {
            if (!running) {
                startServer();
            }
        } else {
            closeServer();
        }
        if (TriggerContract.isBtEnabled(this)) {
            startBluetooth();
        } else {
            stopBluetooth();
        }
        refreshNotification();
        handler.removeCallbacks(sweeper);
        handler.postDelayed(sweeper, 3000);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        sRunning = false;
        sHttpRunning = false;
        handler.removeCallbacks(sweeper);
        TriggerApi.setListener(null);
        var bluetooth = sBluetooth;
        if (bluetooth != null) {
            bluetooth.destroy();
        }
        sBluetooth = null;
        closeServer();
        if (requestPool != null) {
            requestPool.shutdownNow();
        }
        LogHelp.i(TAG, "trigger service stopped");
        super.onDestroy();
    }

    /**
     * 用户从最近任务划掉 App 时，用闹钟做一次兜底重启
     *
     * 系统内存回收由 START_STICKY 处理；强行停止和 OEM 清理由 root 看门狗处理。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        scheduleRestart(this);
        super.onTaskRemoved(rootIntent);
    }

    /**
     * 安排一次服务重启；没有精确闹钟权限时退回非精确闹钟
     */
    private static void scheduleRestart(Context context) {
        if (!TriggerContract.isEnabled(context) && !TriggerContract.isBtEnabled(context)) {
            return;
        }
        try {
            var manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (manager == null) {
                return;
            }
            var intent = new Intent(context, TriggerService.class).setAction(ACTION_START);
            var pending = PendingIntent.getForegroundService(context, RESTART_REQUEST_CODE,
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            var at = System.currentTimeMillis() + RESTART_DELAY_MS;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            }
            LogHelp.i(TAG, "trigger service restart scheduled");
        } catch (Throwable e) {
            LogHelp.w(TAG, "schedule trigger restart failed: " + e.getMessage());
        }
    }

    private static void cancelRestart(Context context) {
        try {
            var manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (manager == null) {
                return;
            }
            var intent = new Intent(context, TriggerService.class).setAction(ACTION_START);
            var pending = PendingIntent.getForegroundService(context, RESTART_REQUEST_CODE,
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.cancel(pending);
            pending.cancel();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startServer() {
        running = true;
        var host = TriggerContract.bind(this);
        var port = TriggerContract.port(this);
        acceptThread = new Thread(() -> {
            try {
                var socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(host, port));
                serverSocket = socket;
                sHttpRunning = true;
                var text = "监听 " + host + ":" + port;
                refreshNotification();
                LogHelp.i(TAG, "trigger http listening on " + text);
                while (running) {
                    var client = socket.accept();
                    requestPool.execute(() -> handle(client));
                }
            } catch (Throwable e) {
                running = false;
                sHttpRunning = false;
                refreshNotification();
                LogHelp.e(TAG, "trigger http start failed: " + e.getMessage(), e);
            }
        }, "xp-trigger-http");
        acceptThread.start();
    }

    private void closeServer() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
            // 忽略
        }
        serverSocket = null;
        sHttpRunning = false;
    }

    /**
     * Android 14+ 需要显式声明前台服务类型；蓝牙权限缺失时退回 specialUse
     */
    private void startForegroundCompat() {
        var notification = buildNotification(buildStatusText());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification);
            return;
        }
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        if (TriggerContract.isBtEnabled(this)) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        }
        try {
            startForeground(NOTIFICATION_ID, notification, type);
        } catch (Throwable e) {
            LogHelp.w(TAG, "startForeground with connectedDevice failed: " + e.getMessage());
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
    }

    private void startBluetooth() {
        var bluetooth = sBluetooth;
        if (bluetooth == null) {
            bluetooth = new BluetoothTriggerManager(this);
            bluetooth.setStatusListener(() -> handler.post(this::refreshNotification));
            sBluetooth = bluetooth;
        }
        if (!bluetooth.isRunning()) {
            var ok = bluetooth.start();
            LogHelp.i(TAG, "bluetooth trigger start " + (ok ? "ok" : "failed"));
        }
        refreshNotification();
    }

    private void stopBluetooth() {
        var bluetooth = sBluetooth;
        if (bluetooth != null) {
            bluetooth.stop();
        }
        refreshNotification();
    }

    /**
     * 写入心跳文件，供 root 看门狗判断服务是否还活着
     *
     * 仅蓝牙模式没有 HTTP 端口，看门狗必须依赖这个文件。
     */
    private void writeHeartbeat() {
        lastHeartbeat = System.currentTimeMillis();
        try (var out = new FileOutputStream(new File(getFilesDir(), HEARTBEAT_FILE))) {
            out.write(String.valueOf(lastHeartbeat).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            LogHelp.w(TAG, "write heartbeat failed: " + e.getMessage());
        }
    }

    private String buildStatusText() {
        var text = new StringBuilder();
        if (TriggerContract.isEnabled(this)) {
            text.append("HTTP ").append(TriggerContract.port(this))
                .append(sHttpRunning ? " 监听中" : " 启动中");
        }
        if (TriggerContract.isBtEnabled(this)) {
            if (text.length() > 0) {
                text.append(" · ");
            }
            text.append("BLE ");
            var bluetooth = sBluetooth;
            if (bluetooth == null || !bluetooth.isRunning()) {
                text.append("启动中");
            } else if (bluetooth.isAdvertising()) {
                text.append("广播中");
            } else {
                text.append("已启动");
            }
            var connected = bluetooth == null ? 0 : bluetooth.connectedCount();
            if (connected > 0) {
                text.append(" · 已连接 ").append(connected);
            }
        }
        return text.length() == 0 ? "正在启动…" : text.toString();
    }

    private void refreshNotification() {
        updateNotification(buildStatusText());
    }

    private final Runnable sweeper = new Runnable() {
        @Override
        public void run() {
            if (!sRunning) {
                return;
            }
            try {
                if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_INTERVAL_MS) {
                    writeHeartbeat();
                }
                for (var record : TriggerStore.expireAll(TriggerService.this)) {
                    TriggerApi.notifyTerminal(TriggerService.this, record);
                }
            } catch (Throwable e) {
                LogHelp.e(TAG, "trigger sweep failed: " + e.getMessage(), e);
            }
            handler.postDelayed(this, 3000);
        }
    };

    /**
     * 处理单个 HTTP 连接
     */
    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(15000);
            var input = new BufferedInputStream(socket.getInputStream());
            var output = socket.getOutputStream();

            var requestLine = readLine(input);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            var parts = requestLine.split(" ");
            if (parts.length < 2) {
                send(output, 400, "Bad Request", errorJson(TriggerContract.E_BAD_REQUEST,
                    "invalid request line"));
                return;
            }
            var method = parts[0].toUpperCase(Locale.ROOT);
            var target = parts[1];
            var path = target;
            var queryIndex = path.indexOf('?');
            if (queryIndex >= 0) {
                path = path.substring(0, queryIndex);
            }

            var headers = readHeaders(input);
            var length = parseInt(headers.get("content-length"), 0);
            if (length < 0 || length > MAX_BODY) {
                send(output, 413, "Payload Too Large", errorJson(TriggerContract.E_BAD_REQUEST,
                    "body too large"));
                return;
            }
            var body = length == 0 ? "" : new String(readN(input, length), StandardCharsets.UTF_8);

            if (!authorized(headers)) {
                send(output, 401, "Unauthorized", errorJson(TriggerContract.E_UNAUTHORIZED,
                    "invalid token"));
                return;
            }
            var remote = socket.getInetAddress() == null
                ? "unknown" : socket.getInetAddress().getHostAddress();
            if (!allowRequest(remote)) {
                send(output, 429, "Too Many Requests", errorJson(TriggerContract.E_RATE_LIMITED,
                    "too many requests"));
                return;
            }

            if ("GET".equals(method) && "/v1/health".equals(path)) {
                sendJson(output, 200, TriggerApi.health(this));
                return;
            }
            if ("POST".equals(method) && "/v1/resolve".equals(path)) {
                sendJson(output, 200, TriggerApi.resolve(this, parseBody(body)));
                return;
            }
            if ("GET".equals(method) && "/v1/friends".equals(path)) {
                var params = parseQuery(target);
                var offset = parseInt(params.get("offset"), 0);
                var limit = parseInt(params.get("limit"), 0);
                var keyword = params.get("keyword");
                if (keyword == null) {
                    keyword = params.get("q");
                }
                sendJson(output, 200, TriggerApi.listFriends(this, offset, limit, keyword));
                return;
            }
            if ("POST".equals(method) && "/v1/call".equals(path)) {
                var result = TriggerApi.startCall(this, parseBody(body));
                var accepted = result.optBoolean("ok", false)
                    && TriggerContract.S_ACCEPTED.equals(result.optString("state", ""));
                sendJson(output, accepted ? 202 : 200, result);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/v1/result/")) {
                var id = URLDecoder.decode(path.substring("/v1/result/".length()),
                    StandardCharsets.UTF_8.name());
                sendJson(output, 200, TriggerApi.query(this, id));
                return;
            }
            if ("GET".equals(method) && ("/".equals(path) || "/v1".equals(path))) {
                sendJson(output, 200, helpJson());
                return;
            }
            sendJson(output, 404, errorJson(TriggerContract.E_NOT_FOUND, "unknown endpoint"));
        } catch (Throwable e) {
            LogHelp.w(TAG, "handle request failed: " + e.getMessage());
        }
    }

    private boolean authorized(Map<String, String> headers) {
        var expected = TriggerContract.token(this);
        var header = headers.get("authorization");
        String provided = null;
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            provided = header.substring(7).trim();
        } else {
            provided = headers.get("x-xp-token");
        }
        return safeEquals(expected, provided);
    }

    private boolean allowRequest(String remote) {
        var limit = TriggerContract.rateLimit(this);
        if (limit <= 0) {
            return true;
        }
        var now = System.currentTimeMillis();
        var bucket = rateBuckets.computeIfAbsent(remote, key -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && now - bucket.peekFirst() > 60_000L) {
                bucket.pollFirst();
            }
            if (bucket.size() >= limit) {
                return false;
            }
            bucket.addLast(now);
        }
        return true;
    }

    private static Map<String, String> readHeaders(InputStream input) throws Exception {
        var headers = new HashMap<String, String>();
        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            var index = line.indexOf(':');
            if (index <= 0) {
                continue;
            }
            headers.put(line.substring(0, index).trim().toLowerCase(Locale.ROOT),
                line.substring(index + 1).trim());
        }
        return headers;
    }

    /**
     * 读取一行，最多 MAX_HEADER 字节
     */
    private static String readLine(InputStream input) throws Exception {
        var buffer = new ByteArrayOutputStream();
        int read;
        while ((read = input.read()) != -1) {
            if (read == '\n') {
                break;
            }
            if (read != '\r') {
                buffer.write(read);
            }
            if (buffer.size() > MAX_HEADER) {
                throw new IllegalStateException("header too large");
            }
        }
        if (read == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private static byte[] readN(InputStream input, int length) throws Exception {
        var data = new byte[length];
        var offset = 0;
        while (offset < length) {
            var read = input.read(data, offset, length - offset);
            if (read == -1) {
                break;
            }
            offset += read;
        }
        if (offset == length) {
            return data;
        }
        var copy = new byte[offset];
        System.arraycopy(data, 0, copy, 0, offset);
        return copy;
    }

    private static JSONObject parseBody(String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject helpJson() {
        var json = new JSONObject();
        try {
            json.put("ok", true);
            json.put("service", "XpWeChatCall external trigger");
            json.put("endpoints", new org.json.JSONArray()
                .put("GET /v1/health")
                .put("POST /v1/resolve")
                .put("GET /v1/friends")
                .put("POST /v1/call")
                .put("GET /v1/result/{request_id}"));
            json.put("auth", "Authorization: Bearer <token>");
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }

    private static JSONObject errorJson(String code, String message) {
        var json = new JSONObject();
        try {
            json.put("ok", false);
            json.put("error", code);
            json.put("message", message);
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }

    /**
     * 直接发送 JSON；业务错误一律 200，调用方以 ok 字段为准
     */
    private static void sendJson(OutputStream output, int status, JSONObject json) throws Exception {
        send(output, status, reason(status), json);
    }

    private static void send(OutputStream output, int status, String reason, JSONObject json)
        throws Exception {
        var bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        var header = "HTTP/1.1 " + status + " " + reason + "\r\n"
            + "Content-Type: application/json; charset=utf-8\r\n"
            + "Content-Length: " + bytes.length + "\r\n"
            + "Connection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 202:
                return "Accepted";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 404:
                return "Not Found";
            case 413:
                return "Payload Too Large";
            case 429:
                return "Too Many Requests";
            default:
                return "Error";
        }
    }

    private static boolean safeEquals(String expected, String provided) {
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
     * 解析 URL 查询参数，同名参数保留第一个
     */
    private static Map<String, String> parseQuery(String target) {
        var params = new HashMap<String, String>();
        var index = target.indexOf('?');
        if (index < 0 || index + 1 >= target.length()) {
            return params;
        }
        for (var pair : target.substring(index + 1).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            var split = pair.indexOf('=');
            var key = split < 0 ? pair : pair.substring(0, split);
            var value = split < 0 ? "" : pair.substring(split + 1);
            try {
                key = URLDecoder.decode(key, StandardCharsets.UTF_8.name());
                value = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
                // 保留原值
            }
            params.putIfAbsent(key, value);
        }
        return params;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void createChannel() {
        var manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        var channel = new NotificationChannel(CHANNEL, "外部触发服务",
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持 webhook 与蓝牙触发接口运行");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        var open = new Intent(this, TriggerSettingsActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        var contentIntent = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        var stopIntent = PendingIntent.getService(this, 1,
            new Intent(this, TriggerService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("微信快捷通话 · 外部触发")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_launcher), "停止", stopIntent).build())
            .build();
    }

    private void updateNotification(String text) {
        var manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
