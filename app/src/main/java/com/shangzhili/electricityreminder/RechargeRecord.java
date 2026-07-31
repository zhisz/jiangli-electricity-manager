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
    /** 仅官方订单接口确认的充值，才允许参与小时级余额趋势的精确校正。 */
    public final boolean officiallyConfirmed;

    public RechargeRecord(long id, long timestamp, double amount, ZoneId zoneId) {
        this(id, timestamp, amount, zoneId, true);
    }

    public RechargeRecord(
            long id,
            long timestamp,
            double amount,
            ZoneId zoneId,
            boolean officiallyConfirmed
    ) {
        this.id = id;
        this.timestamp = timestamp;
        this.date = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate();
        this.time = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalTime()
                .withSecond(0).withNano(0);
        this.amount = amount;
        this.officiallyConfirmed = officiallyConfirmed;
    }
}
