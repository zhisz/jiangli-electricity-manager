package com.shangzhili.electricityreminder;

import java.util.Locale;

/** 表示一天中的一个监测时间点；只保存时和分，不与某个具体日期绑定。 */
public final class DailyCheckTime implements Comparable<DailyCheckTime> {
    public final int hour;
    public final int minute;

    public DailyCheckTime(int hour, int minute) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("检查时间必须是 00:00–23:59");
        }
        this.hour = hour;
        this.minute = minute;
    }

    /** 固定宽度键同时用于本地 JSON 和闹钟 URI，例如 08:05 保存为 08:05。 */
    public String key() {
        return String.format(Locale.CHINA, "%02d:%02d", hour, minute);
    }

    @Override
    public int compareTo(DailyCheckTime other) {
        return hour != other.hour ? hour - other.hour : minute - other.minute;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof DailyCheckTime)) return false;
        DailyCheckTime other = (DailyCheckTime) value;
        return hour == other.hour && minute == other.minute;
    }

    @Override
    public int hashCode() {
        return hour * 60 + minute;
    }
}
