package com.shangzhili.electricityreminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * 用一次性滚动闹钟保证应用被划出最近任务后仍能检查更新和公告。
 *
 * <p>不使用常驻服务。每次只唤醒 Receiver 去提交一个受网络约束的短 Worker，下一次闹钟
 * 在 Receiver 开头先行安排。15 分钟兼顾通知时效、服务器流量和手机耗电。</p>
 */
public final class RemoteMessageScheduler {
    static final long INTERVAL_MILLIS = 15 * 60_000L;
    private static final long INITIAL_DELAY_MILLIS = 60_000L;
    private static final int REQUEST_CODE = 4101;

    private RemoteMessageScheduler() {}

    public static void ensureScheduled(Context context) {
        scheduleAt(context, System.currentTimeMillis() + INITIAL_DELAY_MILLIS);
    }

    static void scheduleNext(Context context) {
        scheduleAt(context, System.currentTimeMillis() + INTERVAL_MILLIS);
    }

    private static void scheduleAt(Context context, long triggerAtMillis) {
        Context app = context.getApplicationContext();
        AlarmManager manager = app.getSystemService(AlarmManager.class);
        PendingIntent operation = pendingIntent(app);
        manager.cancel(operation);
        if (Scheduler.canScheduleExactAlarms(app)) {
            manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, operation
            );
        } else {
            // 未授予精确闹钟时仍保留省电友好的系统调度，可能会有几分钟延迟。
            manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, operation
            );
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, RemoteMessageReceiver.class)
                .setAction(RemoteMessageReceiver.ACTION_CHECK_REMOTE_MESSAGES);
        return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
