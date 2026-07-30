package com.shangzhili.electricityreminder;

/** 折线图使用的单次历史采样。 */
public final class HistoryPoint {
    public final long timestamp;
    public final double surplus;
    public final double amount;

    public HistoryPoint(long timestamp, double surplus, double amount) {
        this.timestamp = timestamp;
        this.surplus = surplus;
        this.amount = amount;
    }
}
