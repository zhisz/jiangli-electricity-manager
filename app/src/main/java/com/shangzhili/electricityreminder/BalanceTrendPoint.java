package com.shangzhili.electricityreminder;

/**
 * 小时级余额趋势中的一个采样点，以及“上一个采样 → 当前采样”区间的耗电结果。
 *
 * <p>把区间速率预先算好，绘图 View 只负责展示，不在 onDraw 中重复执行充值匹配和
 * 单价推断。first point 没有前一区间，因此 rateValid 通常为 false。</p>
 */
public final class BalanceTrendPoint {
    public final HistoryPoint reading;
    /** 延迟数据源平滑后用于画线的估算余额；原始接口值仍完整保留在 reading 中。 */
    public final double displayedSurplus;
    public final double displayedAmount;
    public final long intervalStart;
    public final double usageKwh;
    public final double costAmount;
    public final double rateKwhPerHour;
    public final double costPerHour;
    public final double rechargeAmount;
    public final int rechargeCount;
    public final boolean rateValid;
    public final boolean unmatchedIncrease;
    /** true 表示速率由跨越多个小时的累计差值均摊得到，而不是一小时内直接确认。 */
    public final boolean estimated;
    /** 数据源仍未发生下一次变化时为 true；这种尾部区间不得画成零用电。 */
    public final boolean awaitingSourceUpdate;
    /** 本次均摊覆盖的完整观测窗口小时数，用于向用户解释估算精度。 */
    public final double estimateSpanHours;

    public BalanceTrendPoint(
            HistoryPoint reading,
            double displayedSurplus,
            double displayedAmount,
            long intervalStart,
            double usageKwh,
            double costAmount,
            double rateKwhPerHour,
            double costPerHour,
            double rechargeAmount,
            int rechargeCount,
            boolean rateValid,
            boolean unmatchedIncrease,
            boolean estimated,
            boolean awaitingSourceUpdate,
            double estimateSpanHours
    ) {
        this.reading = reading;
        this.displayedSurplus = displayedSurplus;
        this.displayedAmount = displayedAmount;
        this.intervalStart = intervalStart;
        this.usageKwh = usageKwh;
        this.costAmount = costAmount;
        this.rateKwhPerHour = rateKwhPerHour;
        this.costPerHour = costPerHour;
        this.rechargeAmount = rechargeAmount;
        this.rechargeCount = rechargeCount;
        this.rateValid = rateValid;
        this.unmatchedIncrease = unmatchedIncrease;
        this.estimated = estimated;
        this.awaitingSourceUpdate = awaitingSourceUpdate;
        this.estimateSpanHours = estimateSpanHours;
    }
}
