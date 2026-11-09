package com.zgcwkj.xpwechatcall.trigger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.TriggerContract;

/**
 * 开机、升级或 root 看门狗唤醒后恢复外部触发服务
 *
 * 只在用户已经开启外部触发时启动，避免无谓的常驻通知。
 * 看门狗通过显式广播调用这里，而不是直接 start-foreground-service：
 * TriggerService 保持 exported=false，外部 App 无法直接启动或停止它。
 */
public class BootReceiver extends BroadcastReceiver {

    /** root 看门狗使用的唤醒 action */
    public static final String ACTION_WATCHDOG = "com.zgcwkj.xpwechatcall.action.WATCHDOG_START";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        var action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            && !ACTION_WATCHDOG.equals(action)) {
            return;
        }
        if (!TriggerContract.isEnabled(context) && !TriggerContract.isBtEnabled(context)) {
            return;
        }
        try {
            TriggerService.start(context);
            LogHelp.i("XpWeChatCall", "trigger service restored: " + action);
        } catch (Throwable e) {
            LogHelp.e("XpWeChatCall", "restore trigger service failed: " + e.getMessage(), e);
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // 看门狗脚本随 App 版本升级，升级后重新写入，避免旧脚本缺少心跳检测
            new Thread(() -> {
                if (WatchdogHelper.isScriptPresent(context)) {
                    WatchdogHelper.install(context);
                }
            }, "xp-watchdog-upgrade").start();
        }
    }
}
