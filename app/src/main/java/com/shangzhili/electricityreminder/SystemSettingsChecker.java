package com.shangzhili.electricityreminder;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

/** 使用标准 Android API 检查后台提醒所需设置。 */
public final class SystemSettingsChecker {
    private SystemSettingsChecker() {}

    public static SystemSettingsStatus check(Context context) {
        Context appContext = context.getApplicationContext();
        NotificationManager notificationManager =
                appContext.getSystemService(NotificationManager.class);

        boolean runtimePermission = Build.VERSION.SDK_INT < 33
                || appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        NotificationChannel channel = notificationManager.getNotificationChannel(
                NotificationHelper.ALERT_CHANNEL
        );
        boolean channelReady = channel != null
                && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        boolean notificationsReady = runtimePermission
                && notificationManager.areNotificationsEnabled()
                && channelReady;

        boolean exactAlarmReady = Scheduler.canScheduleExactAlarms(appContext);
        PowerManager powerManager = appContext.getSystemService(PowerManager.class);
        boolean batteryReady = powerManager.isIgnoringBatteryOptimizations(
                appContext.getPackageName()
        );
        boolean autoStartConfirmed = new SetupPreferences(appContext).isAutoStartConfirmed();
        return new SystemSettingsStatus(
                notificationsReady, exactAlarmReady, batteryReady, autoStartConfirmed
        );
    }
}
