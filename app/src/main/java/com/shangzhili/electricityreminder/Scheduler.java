package com.shangzhili.electricityreminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** 为每个 roomId 分别管理整点采样闹钟和低余额重复闹钟。 */
public final class Scheduler {
    private static final int DAILY_ALARM_REQUEST_CODE = 2001;
    private static final int REPEAT_ALARM_REQUEST_CODE = 2002;
    /** AlarmReceiver 用这个稳定键区分 0.16.0 整点任务与旧版自定义时间任务。 */
    public static final String HOURLY_TIME_KEY = "hourly";

    private Scheduler() {}

    /**
     * 0.16.0 起监测固定为每个整点。参数仍保留是为了取消升级前已经登记到系统中的
     * 多时间闹钟；取消完成后只保留一个“下一整点”滚动闹钟，不会同时注册 24 个闹钟。
     */
    public static void scheduleAll(
            Context context, String roomId, List<DailyCheckTime> checkTimes
    ) {
        cancelOldDailyAlarms(context, roomId, checkTimes);
        cancelLegacyDailyAlarm(context, roomId);
        scheduleNextHourly(context, roomId);
    }

    /** 每次只预约下一个整点；Receiver 触发后会先预约下一小时，再交给 WorkManager 查询。 */
    public static long scheduleNextHourly(Context context, String roomId) {
        Context appContext = context.getApplicationContext();
        AlarmManager alarmManager = appContext.getSystemService(AlarmManager.class);
        long triggerAtMillis = nextWholeHour(
                System.currentTimeMillis(), TimeZone.getDefault()
        );
        setAlarm(
                appContext,
                alarmManager,
                triggerAtMillis,
                hourlyAlarmPendingIntent(appContext, roomId)
        );
        return triggerAtMillis;
    }

    public static long scheduleRepeat(
            Context context, String roomId, double repeatMinutes
    ) {
        Context appContext = context.getApplicationContext();
        AlarmManager alarmManager = appContext.getSystemService(AlarmManager.class);
        long intervalMillis = Math.max(60_000L, (long) (repeatMinutes * 60_000L));
        long triggerAtMillis = System.currentTimeMillis() + intervalMillis;
        setAlarm(
                appContext,
                alarmManager,
                triggerAtMillis,
                repeatAlarmPendingIntent(appContext, roomId)
        );
        return triggerAtMillis;
    }

    public static void cancelRepeat(Context context, String roomId) {
        cancelRepeatAlarm(context, roomId);
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(RepeatAlarmReceiver.workName(roomId));
    }

    public static void cancelRepeatAlarm(Context context, String roomId) {
        Context appContext = context.getApplicationContext();
        AlarmManager alarmManager = appContext.getSystemService(AlarmManager.class);
        alarmManager.cancel(repeatAlarmPendingIntent(appContext, roomId));
    }

    /** 删除房间时同时取消它的整点闹钟、重复闹钟和尚未执行的后台查询。 */
    public static void cancelAllForRoom(Context context, String roomId) {
        Context appContext = context.getApplicationContext();
        RoomRepository repository = new RoomRepository(appContext);
        if (repository.contains(roomId)) {
            cancelDailyAlarms(appContext, roomId, repository.load(roomId).checkTimes);
        } else {
            cancelLegacyDailyAlarm(appContext, roomId);
        }
        cancelRepeat(appContext, roomId);
        WorkManager.getInstance(appContext).cancelUniqueWork(AlarmReceiver.legacyWorkName(roomId));
    }

    /** 暂停监测或重建计划时，同时取消整点任务与升级前遗留的自定义时间任务。 */
    public static void cancelDailyAlarms(
            Context context, String roomId, List<DailyCheckTime> checkTimes
    ) {
        Context appContext = context.getApplicationContext();
        cancelOldDailyAlarms(appContext, roomId, checkTimes);
        cancelLegacyDailyAlarm(appContext, roomId);
        appContext.getSystemService(AlarmManager.class)
                .cancel(hourlyAlarmPendingIntent(appContext, roomId));
        WorkManager.getInstance(appContext)
                .cancelUniqueWork(AlarmReceiver.workName(roomId, HOURLY_TIME_KEY));
        WorkManager.getInstance(appContext).cancelUniqueWork(AlarmReceiver.legacyWorkName(roomId));
    }

    /** 开机、改时区或应用升级后遍历所有房间，逐个恢复独立计划。 */
    public static void restoreAllConfigured(Context context) {
        RoomRepository repository = new RoomRepository(context);
        for (String roomId : repository.listRoomIds()) {
            if (!repository.isConfigured(roomId)
                    || !repository.isMonitoringEnabled(roomId)) continue;
            AppConfig config = repository.load(roomId);
            scheduleAll(context, roomId, config.checkTimes);
            if (new MonitorState(context, roomId).isLowAlertActive()) {
                scheduleRepeat(context, roomId, config.repeatMinutes);
            }
        }
    }

    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        return alarmManager.canScheduleExactAlarms();
    }

    /**
     * 纯时间计算单独保留参数，便于单元测试跨日边界；生产调用传入当前时间与系统时区。
     */
    static long nextWholeHour(long nowMillis, TimeZone timeZone) {
        Calendar now = Calendar.getInstance(timeZone);
        now.setTimeInMillis(nowMillis);
        Calendar next = (Calendar) now.clone();
        next.add(Calendar.HOUR_OF_DAY, 1);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        return next.getTimeInMillis();
    }

    private static void setAlarm(
            Context context,
            AlarmManager alarmManager,
            long triggerAtMillis,
            PendingIntent operation
    ) {
        alarmManager.cancel(operation);
        if (canScheduleExactAlarms(context)) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, operation
            );
        } else {
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, operation
            );
        }
    }

    /**
     * PendingIntent 判断是否相同时不会比较 extras，所以必须把 roomId 写入 data URI。
     * 否则多个房间虽然 extras 不同，系统仍会把它们当成同一个闹钟互相覆盖。
     */
    private static PendingIntent dailyAlarmPendingIntent(
            Context context, String roomId, DailyCheckTime time
    ) {
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(AlarmReceiver.ACTION_DAILY_CHECK)
                // 时间键必须进入 data URI；extras 不参与 PendingIntent 相等判断。
                .setData(alarmUri("daily/" + time.key(), roomId))
                .putExtra(AlarmReceiver.EXTRA_ROOM_ID, roomId)
                .putExtra(AlarmReceiver.EXTRA_TIME_KEY, time.key());
        return PendingIntent.getBroadcast(
                context,
                DAILY_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    /** 整点模式所有小时复用同一个 PendingIntent；每次触发后覆盖为下一整点。 */
    private static PendingIntent hourlyAlarmPendingIntent(Context context, String roomId) {
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(AlarmReceiver.ACTION_DAILY_CHECK)
                .setData(alarmUri("hourly", roomId))
                .putExtra(AlarmReceiver.EXTRA_ROOM_ID, roomId)
                .putExtra(AlarmReceiver.EXTRA_TIME_KEY, HOURLY_TIME_KEY);
        return PendingIntent.getBroadcast(
                context,
                DAILY_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    /** 仅清除旧版用户自定义的 HH:mm 闹钟及其工作，不触碰正在执行的整点任务。 */
    private static void cancelOldDailyAlarms(
            Context context, String roomId, List<DailyCheckTime> checkTimes
    ) {
        Context appContext = context.getApplicationContext();
        AlarmManager alarmManager = appContext.getSystemService(AlarmManager.class);
        for (DailyCheckTime time : checkTimes) {
            alarmManager.cancel(dailyAlarmPendingIntent(appContext, roomId, time));
            WorkManager.getInstance(appContext)
                    .cancelUniqueWork(AlarmReceiver.workName(roomId, time.key()));
        }
    }

    /** 取消 0.10.1 及以前使用、不含时间键的单每日闹钟。 */
    private static void cancelLegacyDailyAlarm(Context context, String roomId) {
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(AlarmReceiver.ACTION_DAILY_CHECK)
                .setData(alarmUri("daily", roomId))
                .putExtra(AlarmReceiver.EXTRA_ROOM_ID, roomId);
        PendingIntent legacy = PendingIntent.getBroadcast(
                context, DAILY_ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        context.getSystemService(AlarmManager.class).cancel(legacy);
    }

    private static PendingIntent repeatAlarmPendingIntent(Context context, String roomId) {
        Intent intent = new Intent(context, RepeatAlarmReceiver.class)
                .setAction(RepeatAlarmReceiver.ACTION_REPEAT_CHECK)
                .setData(alarmUri("repeat", roomId))
                .putExtra(AlarmReceiver.EXTRA_ROOM_ID, roomId);
        return PendingIntent.getBroadcast(
                context,
                REPEAT_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static Uri alarmUri(String type, String roomId) {
        return Uri.parse("electricity-reminder://alarm/" + type + "/" + Uri.encode(roomId));
    }
}
