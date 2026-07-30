package com.shangzhili.electricityreminder;

import java.time.LocalDate;

/** 柱状图中的一天；没有有效相邻采样时 hasData 为 false，不会被误画成零用电。 */
public final class DailyUsagePoint {
    public final LocalDate date;
    public final double usageKwh;
    public final double costAmount;
    public final boolean hasData;

    public DailyUsagePoint(
            LocalDate date,
            double usageKwh,
            double costAmount,
            boolean hasData
    ) {
        this.date = date;
        this.usageKwh = usageKwh;
        this.costAmount = costAmount;
        this.hasData = hasData;
    }
}
