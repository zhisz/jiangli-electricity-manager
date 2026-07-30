package com.shangzhili.electricityreminder;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 校付宝收银台地址白名单。
 *
 * <p>充值接口返回的网址属于服务端数据，不能未经校验直接交给支付过渡页。这里同时限制
 * HTTPS、精确域名和 /pay/ 路径，避免接口异常或被篡改时把用户带到仿冒收银台。</p>
 */
public final class RechargeCheckoutUrlValidator {
    private static final String CHECKOUT_HOST = "pay.xiaofubao.com";
    private static final String WECHAT_H5_HOST = "wx.tenpay.com";
    private static final String WECHAT_H5_BACKUP_HOST = "payapp.weixin.qq.com";

    private RechargeCheckoutUrlValidator() {
    }

    public static String requireTrusted(String value) throws java.io.IOException {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            boolean trusted = "https".equalsIgnoreCase(uri.getScheme())
                    && CHECKOUT_HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getPath() != null
                    && uri.getPath().startsWith("/pay/")
                    && uri.getRawQuery() != null
                    && !uri.getRawQuery().isEmpty();
            if (!trusted) throw new java.io.IOException("校付宝返回了不受信任的收银台地址");
            return uri.toASCIIString();
        } catch (URISyntaxException exception) {
            throw new java.io.IOException("校付宝返回的收银台地址格式无效", exception);
        }
    }

    /**
     * 从已校验的收银台地址中提取官方支付单号。
     *
     * <p>不能用字符串 contains 或 split("tran_no=") 草率提取：查询参数可能调整顺序、
     * URL 编码或重复出现。这里只接受唯一的 tran_no，且值必须是校付宝当前使用的纯数字
     * 长单号；异常地址直接阻止付款，避免后续无法确认具体订单。</p>
     */
    public static String requirePaymentNo(String checkoutUrl)
            throws java.io.IOException {
        String trusted = requireTrusted(checkoutUrl);
        try {
            URI uri = new URI(trusted);
            String paymentNo = null;
            for (String pair : uri.getRawQuery().split("&")) {
                int separator = pair.indexOf('=');
                String rawKey = separator >= 0 ? pair.substring(0, separator) : pair;
                if (!"tran_no".equals(URLDecoder.decode(
                        rawKey, StandardCharsets.UTF_8.name()
                ))) {
                    continue;
                }
                if (paymentNo != null || separator < 0) {
                    throw new java.io.IOException("校付宝收银台包含重复或无效的支付单号");
                }
                paymentNo = URLDecoder.decode(
                        pair.substring(separator + 1),
                        StandardCharsets.UTF_8.name()
                );
            }
            if (paymentNo == null || !paymentNo.matches("\\d{10,40}")) {
                throw new java.io.IOException("校付宝收银台缺少有效支付单号");
            }
            return paymentNo;
        } catch (URISyntaxException exception) {
            throw new java.io.IOException("校付宝收银台地址格式无效", exception);
        }
    }

    /**
     * 判断支付 WebView 是否可以继续加载某个 HTTPS 页面。
     *
     * <p>初始页面只能来自校付宝收银台；用户点击微信支付后，页面可能进入微信支付官方
     * H5 中间页。这里使用精确主机名，不接受子域名、HTTP 或带 userInfo 的伪装地址。
     * 任何不在列表内的网页都不会留在承载支付令牌的 WebView 中。</p>
     */
    public static boolean isTrustedPaymentWebPage(String value) {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                return false;
            }
            String host = uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (CHECKOUT_HOST.equalsIgnoreCase(host)) {
                return path.startsWith("/pay/");
            }
            if (WECHAT_H5_HOST.equalsIgnoreCase(host)) {
                return path.startsWith("/cgi-bin/mmpayweb-bin/");
            }
            return WECHAT_H5_BACKUP_HOST.equalsIgnoreCase(host) && !path.isEmpty();
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    /**
     * 只允许微信 H5 支付生成的标准唤起指令。
     *
     * <p>不能因为 URI 的 scheme 是 {@code weixin} 就全部交给微信，否则被篡改的网页
     * 可以借此打开微信内其他页面。H5 支付使用固定的 {@code weixin://wap/pay} 入口，
     * 且必须携带一次性查询参数。</p>
     */
    public static String requireTrustedWechatPayCommand(String value)
            throws java.io.IOException {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            boolean trusted = "weixin".equalsIgnoreCase(uri.getScheme())
                    && "wap".equalsIgnoreCase(uri.getHost())
                    && "/pay".equals(uri.getPath())
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() != null
                    && !uri.getRawQuery().isEmpty();
            if (!trusted) throw new java.io.IOException("支付页面返回了无效的微信支付指令");
            return uri.toASCIIString();
        } catch (URISyntaxException exception) {
            throw new java.io.IOException("微信支付指令格式无效", exception);
        }
    }
}
