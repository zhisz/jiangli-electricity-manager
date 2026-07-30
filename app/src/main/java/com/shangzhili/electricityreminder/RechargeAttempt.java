package com.shangzhili.electricityreminder;

/**
 * 一次已经创建校付宝订单、等待确认到账的本地尝试。
 *
 * <p>只保存本地 UUID、房间内部 ID、用户输入金额，以及从收银台 tran_no 提取的官方
 * payNo；不保存收银台 URL、微信身份或支付令牌。payNo 仅保存在本机，用于向校付宝
 * 精确查询本次订单。金额统一使用“分”，避免比较时出现 double 误差。</p>
 */
public final class RechargeAttempt {
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_UNCONFIRMED = "unconfirmed";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_SUPERSEDED = "superseded";

    public final String attemptId;
    public final String roomId;
    public final long requestedCents;
    public final long createdAt;
    public final long verificationGeneration;
    public final long launchedAt;
    public final long returnedAt;
    public final String status;
    public final long rechargeId;
    public final String resultNotice;
    public final boolean resultNoticeShown;
    public final String paymentNo;

    public RechargeAttempt(
            String attemptId,
            String roomId,
            long requestedCents,
            long createdAt,
            long verificationGeneration,
            long launchedAt,
            long returnedAt,
            String status,
            long rechargeId,
            String resultNotice,
            boolean resultNoticeShown,
            String paymentNo
    ) {
        this.attemptId = attemptId;
        this.roomId = roomId;
        this.requestedCents = requestedCents;
        this.createdAt = createdAt;
        this.verificationGeneration = verificationGeneration;
        this.launchedAt = launchedAt;
        this.returnedAt = returnedAt;
        this.status = status;
        this.rechargeId = rechargeId;
        this.resultNotice = resultNotice;
        this.resultNoticeShown = resultNoticeShown;
        this.paymentNo = paymentNo;
    }
}
