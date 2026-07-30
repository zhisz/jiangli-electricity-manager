package com.shangzhili.electricityreminder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 创建充值订单前展示给用户核对的只读信息。
 *
 * <p>这里不保存支付订单、微信身份或收银台地址，只承载校付宝返回的房间显示名、当前余额
 * 和金额限制。充值金额必须先通过该上下文校验，再允许申请一次性提交令牌。</p>
 */
public final class RechargeContext {
    public final String displayRoomName;
    public final double surplus;
    public final double balanceAmount;
    public final BigDecimal minimumAmount;
    public final BigDecimal maximumAmount;
    public final List<String> suggestedAmounts;
    public final String serviceTimeBegin;
    public final String serviceTimeEnd;
    public final String thisSettlingTime;
    public final boolean canBuy;

    public RechargeContext(
            String displayRoomName,
            double surplus,
            double balanceAmount,
            BigDecimal minimumAmount,
            BigDecimal maximumAmount,
            List<String> suggestedAmounts,
            String serviceTimeBegin,
            String serviceTimeEnd,
            String thisSettlingTime,
            boolean canBuy
    ) {
        this.displayRoomName = displayRoomName;
        this.surplus = surplus;
        this.balanceAmount = balanceAmount;
        this.minimumAmount = minimumAmount;
        this.maximumAmount = maximumAmount;
        this.suggestedAmounts = suggestedAmounts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(suggestedAmounts);
        this.serviceTimeBegin = serviceTimeBegin;
        this.serviceTimeEnd = serviceTimeEnd;
        this.thisSettlingTime = thisSettlingTime;
        this.canBuy = canBuy;
    }
}
