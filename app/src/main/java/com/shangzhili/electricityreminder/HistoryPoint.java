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

    public boolean isRechargeChange() {
        /*
         * 新服务端将所有正向余额变化统一标记为“充值”。保留旧标签兼容已下载到本地的
         * 1.2.x 云端历史，避免升级 App 后旧记录突然失去充值跳升效果。
         */
        return "充值".equals(changeType)
                || "疑似充值或平台修正".equals(changeType);
    }
}
