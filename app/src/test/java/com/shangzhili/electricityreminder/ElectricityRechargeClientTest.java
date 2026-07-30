package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

/** 充值功能的金额边界和收银台域名属于资金安全关键路径，必须保持回归覆盖。 */
public final class ElectricityRechargeClientTest {
    @Test
    public void amountIsNormalizedWithoutFloatingPointError() {
        ElectricityRechargeClient client = new ElectricityRechargeClient();
        RechargeContext context = context("1", "10000");

        assertEquals("1", client.normalizeAmount("1.00", context));
        assertEquals("30.5", client.normalizeAmount("30.50", context));
    }

    @Test
    public void amountOutsideRangeOrWithTooManyDecimalsIsRejected() {
        ElectricityRechargeClient client = new ElectricityRechargeClient();
        RechargeContext context = context("1", "10000");

        assertAmountRejected(client, context, "0.99");
        assertAmountRejected(client, context, "10000.01");
        assertAmountRejected(client, context, "1.001");
        assertAmountRejected(client, context, "不是数字");
    }

    @Test
    public void onlyOfficialHttpsCashierUrlIsAccepted() throws Exception {
        String official = "https://pay.xiaofubao.com/pay/unified/toCashier.shtml"
                + "?tran_no=2607000000000000001&authPlatform=WECHAT_H5&s=masked";
        assertEquals(official, RechargeCheckoutUrlValidator.requireTrusted(official));
        assertEquals(
                "2607000000000000001",
                RechargeCheckoutUrlValidator.requirePaymentNo(official)
        );

        assertUrlRejected("http://pay.xiaofubao.com/pay/unified/toCashier.shtml?a=1");
        assertUrlRejected("https://pay.xiaofubao.com.evil.example/pay/cashier?a=1");
        assertUrlRejected("https://user@pay.xiaofubao.com/pay/cashier?a=1");
        assertUrlRejected("https://pay.xiaofubao.com/other/cashier?a=1");
        assertUrlRejected("https://pay.xiaofubao.com/pay/cashier");
    }

    @Test
    public void checkoutMustContainOneNumericOfficialPaymentNumber() {
        assertPaymentNoRejected(
                "https://pay.xiaofubao.com/pay/unified/toCashier.shtml?s=masked"
        );
        assertPaymentNoRejected(
                "https://pay.xiaofubao.com/pay/unified/toCashier.shtml"
                        + "?tran_no=not-a-number&s=masked"
        );
        assertPaymentNoRejected(
                "https://pay.xiaofubao.com/pay/unified/toCashier.shtml"
                        + "?tran_no=2607000000000000001"
                        + "&tran_no=2607000000000000002"
        );
    }

    @Test
    public void paymentWebViewOnlyKeepsOfficialPaymentPages() {
        assertTrue(RechargeCheckoutUrlValidator.isTrustedPaymentWebPage(
                "https://pay.xiaofubao.com/pay/unified/toCashier.shtml?a=masked"
        ));
        assertTrue(RechargeCheckoutUrlValidator.isTrustedPaymentWebPage(
                "https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?a=masked"
        ));
        assertTrue(RechargeCheckoutUrlValidator.isTrustedPaymentWebPage(
                "https://payapp.weixin.qq.com/some/payment/path?a=masked"
        ));

        assertFalse(RechargeCheckoutUrlValidator.isTrustedPaymentWebPage(
                "http://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?a=masked"
        ));
        assertFalse(RechargeCheckoutUrlValidator.isTrustedPaymentWebPage(
                "https://wx.tenpay.com.evil.example/cgi-bin/mmpayweb-bin/checkmweb?a=masked"
        ));
        assertFalse(RechargeCheckoutUrlValidator.isTrustedPaymentWebPage(
                "https://wx.tenpay.com/unrelated/page?a=masked"
        ));
    }

    @Test
    public void onlyStandardWechatH5PayCommandIsAccepted() throws Exception {
        String official = "weixin://wap/pay?prepayid=masked&sign=masked";
        assertEquals(
                official,
                RechargeCheckoutUrlValidator.requireTrustedWechatPayCommand(official)
        );

        assertWechatCommandRejected("weixin://dl/business/?t=masked");
        assertWechatCommandRejected("weixin://evil/pay?prepayid=masked");
        assertWechatCommandRejected("weixin://wap/pay");
        assertWechatCommandRejected("https://wx.tenpay.com/pay?a=masked");
    }

    @Test
    public void orderListUsesExactPaymentNumberInsteadOfAmountGuessing()
            throws Exception {
        JSONObject other = new JSONObject()
                .put("payNo", "2607000000000000002")
                .put("orderNo", "2707000000000000002");
        JSONObject expected = new JSONObject()
                .put("payNo", "2607000000000000001")
                .put("orderNo", "2707000000000000001");
        JSONObject root = new JSONObject().put(
                "rows", new JSONArray().put(other).put(expected)
        );

        assertEquals(
                "2707000000000000001",
                ElectricityRechargeClient.findOrderByPaymentNo(
                        root, "2607000000000000001"
                ).optString("orderNo")
        );
        assertEquals(
                null,
                ElectricityRechargeClient.findOrderByPaymentNo(
                        root, "2607000000000000999"
                )
        );
    }

    @Test
    public void officialSuccessIsAcceptedAndUsesOfficialPaymentTime() throws Exception {
        JSONObject detail = officialDetail(2);
        RechargeOrderStatus result =
                ElectricityRechargeClient.parseOfficialOrderDetail(
                        detail,
                        "2607000000000000001",
                        "001001001001001",
                        3_000
                );

        assertEquals(RechargeOrderStatus.SUCCESS, result.state);
        assertEquals(3_000, result.totalCents);
        assertEquals(
                LocalDateTime.of(2026, 7, 30, 8, 15, 20)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toInstant()
                        .toEpochMilli(),
                result.paidAt
        );
    }

    @Test
    public void officialPendingAndFailedRemainDistinct() throws Exception {
        assertEquals(
                RechargeOrderStatus.PENDING,
                ElectricityRechargeClient.parseOfficialOrderDetail(
                        officialDetail(1),
                        "2607000000000000001",
                        "001001001001001",
                        3_000
                ).state
        );
        assertEquals(
                RechargeOrderStatus.FAILED,
                ElectricityRechargeClient.parseOfficialOrderDetail(
                        officialDetail(3),
                        "2607000000000000001",
                        "001001001001001",
                        3_000
                ).state
        );
    }

    @Test
    public void officialOrderMustMatchRoomPaymentNumberAndAmount()
            throws Exception {
        assertOfficialDetailRejected(
                officialDetail(2),
                "2607000000000000999",
                "001001001001001",
                3_000
        );
        assertOfficialDetailRejected(
                officialDetail(2),
                "2607000000000000001",
                "001001001001999",
                3_000
        );
        assertOfficialDetailRejected(
                officialDetail(2),
                "2607000000000000001",
                "001001001001001",
                5_000
        );
    }

    private RechargeContext context(String minimum, String maximum) {
        return new RechargeContext(
                "测试房间", 10, 6,
                new BigDecimal(minimum), new BigDecimal(maximum),
                Arrays.asList("10", "30"), "00:30", "23:30", "", true
        );
    }

    private JSONObject officialDetail(int payStatus) throws Exception {
        return new JSONObject()
                .put("payNo", "2607000000000000001")
                .put("orderNo", "2707000000000000001")
                .put("schoolCode", AppConstants.SCHOOL_CODE)
                .put("roomCode", "001001001001001")
                .put("prodName", "缴电费")
                .put("totalMoney", "30.00")
                .put("payStatus", payStatus)
                .put("payTime", "2026-07-30 08:15:20");
    }

    private void assertAmountRejected(
            ElectricityRechargeClient client,
            RechargeContext context,
            String input
    ) {
        try {
            client.normalizeAmount(input, context);
            fail("金额应被拒绝：" + input);
        } catch (IllegalArgumentException expected) {
            // 预期路径。
        }
    }

    private void assertUrlRejected(String value) {
        try {
            RechargeCheckoutUrlValidator.requireTrusted(value);
            fail("网址应被拒绝：" + value);
        } catch (IOException expected) {
            // 预期路径。
        }
    }

    private void assertPaymentNoRejected(String value) {
        try {
            RechargeCheckoutUrlValidator.requirePaymentNo(value);
            fail("支付单号应被拒绝：" + value);
        } catch (IOException expected) {
            // 预期路径。
        }
    }

    private void assertOfficialDetailRejected(
            JSONObject detail,
            String paymentNo,
            String roomCode,
            long amountCents
    ) {
        try {
            ElectricityRechargeClient.parseOfficialOrderDetail(
                    detail, paymentNo, roomCode, amountCents
            );
            fail("不匹配的官方订单详情应被拒绝");
        } catch (IOException expected) {
            // 预期路径。
        }
    }

    private void assertWechatCommandRejected(String value) {
        try {
            RechargeCheckoutUrlValidator.requireTrustedWechatPayCommand(value);
            fail("微信支付指令应被拒绝：" + value);
        } catch (IOException expected) {
            // 预期路径。
        }
    }
}
