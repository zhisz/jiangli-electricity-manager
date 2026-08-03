package com.shangzhili.electricityreminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 手机重启、手动修改系统时间/时区或应用升级后，系统原有闹钟可能失效或时间含义改变。
 * 接收到这些系统广播后，重新计算每个房间的下一整点，并恢复余额不足复查。
 */
public final class RescheduleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        // Receiver 只应响应 Manifest 中声明的系统广播。显式校验 action 可以避免其他来源
        // 构造一个无关 Intent，诱使应用无意义地反复重排闹钟。
        if (!(Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action))) {
            return;
        }
        Scheduler.restoreAllConfigured(context);
        // 应用升级、重启或系统时间变化后再次确保唯一周期任务存在；不会重复创建。
        UpdateNotificationWorker.schedulePeriodic(context);
        RemoteMessageScheduler.ensureScheduled(context);
    }
}
