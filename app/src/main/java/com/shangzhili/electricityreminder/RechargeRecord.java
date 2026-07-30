package com.shangzhili.electricityreminder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 用户为某个房间手动登记的一笔充值。
 *
 * <p>0.16.0 起保存精确到分钟的时间戳，因此同一天可以登记多笔充值，并能把每一笔分别
 * 标到小时趋势图上。date/time 是按创建对象时传入的本地时区派生出的展示字段。</p>
 */
public final class RechargeRecord {
    public final long id;
    public final long timestamp;
    public final LocalDate date;
    public final LocalTime time;
    public final double amount;

    public RechargeRecord(long id, long timestamp, double amount, ZoneId zoneId) {
        this.id = id;
        this.timestamp = timestamp;
        this.date = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate();
        this.time = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalTime()
                .withSecond(0).withNano(0);
        this.amount = amount;
    }
}
