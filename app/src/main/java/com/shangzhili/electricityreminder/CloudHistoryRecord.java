package com.shangzhili.electricityreminder;

/** 服务器公共采样接口返回的一条可合并余额记录。 */
public final class CloudHistoryRecord {
    public final String sampleKey;
    public final String roomCode;
    public final long queriedAt;
    public final double surplus;
    public final double amount;
    public final String queryResult;
    public final long changeStartAt;
    public final long changeEndAt;
    public final double changeDeltaKwh;
    public final String changeType;

    public CloudHistoryRecord(
            String sampleKey,
            String roomCode,
            long queriedAt,
            double surplus,
            double amount,
            String queryResult
    ) {
        this(
                sampleKey, roomCode, queriedAt, surplus, amount, queryResult,
                0, 0, Double.NaN, ""
        );
    }

    public CloudHistoryRecord(
            String sampleKey,
            String roomCode,
            long queriedAt,
            double surplus,
            double amount,
            String queryResult,
            long changeStartAt,
            long changeEndAt,
            double changeDeltaKwh,
            String changeType
    ) {
        this.sampleKey = sampleKey;
        this.roomCode = roomCode;
        this.queriedAt = queriedAt;
        this.surplus = surplus;
        this.amount = amount;
        this.queryResult = queryResult;
        this.changeStartAt = changeStartAt;
        this.changeEndAt = changeEndAt;
        this.changeDeltaKwh = changeDeltaKwh;
        this.changeType = changeType == null ? "" : changeType;
    }

    public boolean isValidSuccess() {
        return sampleKey != null
                && !sampleKey.trim().isEmpty()
                && roomCode != null
                && roomCode.matches("\\d{15}")
                && queriedAt > 0
                && Double.isFinite(surplus)
                && Double.isFinite(amount)
                && "success".equals(queryResult);
    }
}
