package com.shangzhili.electricityreminder;

/** 一个自然日期区间内的估算用电结果。 */
public final class UsagePeriodStats {
    public final double usageKwh;
    public final double costAmount;
    public final long coveredDays;
    public final int excludedRechargeIntervals;

    public UsagePeriodStats(
            double usageKwh,
            double costAmount,
            long coveredDays,
            int excludedRechargeIntervals
    ) {
        this.usageKwh = usageKwh;
        this.costAmount = costAmount;
        this.coveredDays = coveredDays;
        this.excludedRechargeIntervals = excludedRechargeIntervals;
    }

    public boolean hasData() {
        return coveredDays > 0;
    }
}
