package com.zgcwkj.comm;

import android.content.Context;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 微信进程 -> 模块进程的结果回传
 *
 * 运行在微信进程内，只依赖 Provider，不直接访问模块的存储。
 * 单线程队列保证状态按步骤顺序送达。
 */
public final class CallReporter {

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "xp-call-report");
        thread.setDaemon(true);
        return thread;
    });

    private CallReporter() {
    }

    /**
     * 回传一个状态，request_id 为空时（桌面快捷方式/控件）直接忽略
     */
    public static void report(Context context, String requestId, String state,
                              String error, String detail) {
        if (context == null || requestId == null || requestId.isEmpty()) {
            return;
        }
        var appContext = context.getApplicationContext();
        var json = new JSONObject();
        try {
            json.put("request_id", requestId);
            json.put("state", state);
            if (error != null) {
                json.put("error", error);
            }
            if (detail != null) {
                json.put("detail", detail);
            }
        } catch (Exception e) {
            return;
        }
        var payload = json.toString();
        POOL.execute(() -> {
            try {
                appContext.getContentResolver().call(
                    TriggerContract.CONTENT_URI, TriggerContract.METHOD_REPORT, payload, null);
            } catch (Throwable e) {
                LogHelp.w("XpWeChatCall", "report call result failed: " + e.getMessage());
            }
        });
    }
}
