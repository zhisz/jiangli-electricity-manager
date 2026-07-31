package com.shangzhili.electricityreminder;

/** 折线图使用的单次历史采样。 */
public final class HistoryPoint {
    public final long timestamp;
    public final double surplus;
    public final double amount;
    /** 服务器变化事件的起点和保守类型；本地采样没有该补充信息。 */
    public final long changeStartTimestamp;
    public final String changeType;

    public HistoryPoint(long timestamp, double surplus, double amount) {
        this(timestamp, surplus, amount, 0, "");
    }

    public HistoryPoint(
            long timestamp,
            double surplus,
            double amount,
            long changeStartTimestamp,
            String changeType
    ) {
        this.timestamp = timestamp;
        this.surplus = surplus;
        this.amount = amount;
        this.changeStartTimestamp = changeStartTimestamp;
        this.changeType = changeType == null ? "" : changeType;
    }

    public boolean isSuspectedRechargeOrCorrection() {
        return "疑似充值或平台修正".equals(changeType);
    }
}
