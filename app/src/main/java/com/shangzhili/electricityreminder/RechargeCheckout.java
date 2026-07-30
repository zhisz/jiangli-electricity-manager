package com.shangzhili.electricityreminder;

/**
 * 校付宝创建订单后返回的本地支付上下文。
 *
 * <p>{@code checkoutUrl} 只在内存中交给受限 WebView；{@code paymentNo} 来自该 URL 的
 * {@code tran_no} 参数。抓包和官方缴费记录已经验证：tran_no 与订单记录的 payNo
 * 完全一致，因此可以用它精确关联“本次付款”，不再依赖房间余额涨幅猜测。</p>
 */
public final class RechargeCheckout {
    public final String checkoutUrl;
    public final String paymentNo;

    public RechargeCheckout(String checkoutUrl, String paymentNo) {
        this.checkoutUrl = checkoutUrl;
        this.paymentNo = paymentNo;
    }
}
