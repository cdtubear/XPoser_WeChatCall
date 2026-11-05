package com.zgcwkj.xpwechatcall.trigger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.TriggerContract;

/**
 * 开机后恢复外部触发服务
 *
 * 只在用户已经开启外部触发时启动，避免无谓的常驻通知。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!TriggerContract.isEnabled(context)) {
            return;
        }
        try {
            TriggerService.start(context);
            LogHelp.i("XpWeChatCall", "trigger service restored after boot");
        } catch (Throwable e) {
            LogHelp.e("XpWeChatCall", "restore trigger service failed: " + e.getMessage(), e);
        }
    }
}
