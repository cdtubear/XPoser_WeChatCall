package com.cdtubear.xpwechatcall.voip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.cdtubear.xpwechatcall.trigger.TriggerApi;

/**
 * 通知栏「接听」按钮
 *
 * 只接受本应用自己发出的 PendingIntent（exported=false + 显式 Intent），
 * 避免其它 App 伪造广播替用户接听电话。
 */
public final class VoipActionReceiver extends BroadcastReceiver {

    public static final String ACTION_ANSWER =
        "com.cdtubear.xpwechatcall.action.VOIP_ANSWER";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
            || !ACTION_ANSWER.equals(intent.getAction())) {
            return;
        }
        // 本地按钮不受"外部触发总开关"限制，但只有正在响铃时才会生效
        TriggerApi.answerIncoming(context, null, false, false);
    }
}
