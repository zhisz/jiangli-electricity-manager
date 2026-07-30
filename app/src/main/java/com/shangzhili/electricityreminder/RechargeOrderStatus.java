package com.shangzhili.electricityreminder;

/**
 * 校付宝官方订单接口返回的标准化状态。
 *
 * <p>官方前端对 payStatus 的定义为：1 等待支付、2 支付成功、其他值支付失败。
 * NOT_FOUND 表示缴费记录尚未出现本次 payNo，常见于刚从微信返回、服务端仍在同步；
 * 它不是失败，调用方应按既定短周期继续查询。</p>
 */
public final class RechargeOrderStatus {
    public static final int NOT_FOUND = 0;
    public static final int PENDING = 1;
    public static final int SUCCESS = 2;
    public static final int FAILED = 3;

    public final int state;
    public final String paymentNo;
    public final String orderNo;
    public final long totalCents;
    public final long paidAt;

    private RechargeOrderStatus(
            int state,
            String paymentNo,
            String orderNo,
            long totalCents,
            long paidAt
    ) {
        this.state = state;
        this.paymentNo = paymentNo;
        this.orderNo = orderNo;
        this.totalCents = totalCents;
        this.paidAt = paidAt;
    }

    public static RechargeOrderStatus notFound(String paymentNo) {
        return new RechargeOrderStatus(NOT_FOUND, paymentNo, "", 0, 0);
    }

    public static RechargeOrderStatus of(
            int state,
            String paymentNo,
            String orderNo,
            long totalCents,
            long paidAt
    ) {
        return new RechargeOrderStatus(
                state, paymentNo, orderNo, totalCents, paidAt
        );
    }
}
