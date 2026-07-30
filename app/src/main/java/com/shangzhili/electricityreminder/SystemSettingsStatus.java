package com.shangzhili.electricityreminder;

/** 一次系统设置检查的不可变结果。 */
public final class SystemSettingsStatus {
    public final boolean notificationsReady;
    public final boolean exactAlarmReady;
    public final boolean batteryReady;
    public final boolean autoStartConfirmed;

    public SystemSettingsStatus(
            boolean notificationsReady,
            boolean exactAlarmReady,
            boolean batteryReady,
            boolean autoStartConfirmed
    ) {
        this.notificationsReady = notificationsReady;
        this.exactAlarmReady = exactAlarmReady;
        this.batteryReady = batteryReady;
        this.autoStartConfirmed = autoStartConfirmed;
    }

    public boolean allReady() {
        return notificationsReady && exactAlarmReady && batteryReady && autoStartConfirmed;
    }

    public int missingCount() {
        int count = 0;
        if (!notificationsReady) count++;
        if (!exactAlarmReady) count++;
        if (!batteryReady) count++;
        if (!autoStartConfirmed) count++;
        return count;
    }
}
