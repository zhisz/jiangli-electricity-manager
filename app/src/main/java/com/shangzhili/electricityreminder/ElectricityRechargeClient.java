package com.shangzhili.electricityreminder;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校付宝电费充值客户端。
 *
 * <p>本类负责读取官方充值规则、创建一次订单、取得官方收银台 HTTPS 地址，并在用户
 * 返回后读取校付宝自己的订单记录。真正付款仍始终在校付宝和微信中完成，本应用不接收
 * 支付密码、不拼接微信支付参数。尤其要注意：调用 recharge 后即使网络超时，也禁止自动
 * 重试，否则可能产生重复订单。</p>
 */
public final class ElectricityRechargeClient {
    private static final String BASE_URL = "https://application.xiaofubao.com";
    private static final String PLATFORM = "WECHAT_H5";
    private static final String ELECTRIC_ORDER_SUBTYPE = "100302";
    private static final DateTimeFormatter OFFICIAL_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId OFFICIAL_TIME_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 读取充值规则和当前房间信息。两个接口都只读，可以在金额输入框出现前执行。
     */
    public RechargeContext loadContext(AppConfig config)
            throws IOException, AuthExpiredException {
        config.validate();

        Map<String, String> configFields = new LinkedHashMap<>();
        configFields.put("customType", "1");
        configFields.put("platform", PLATFORM);
        JSONObject ruleData = requireDataObject(post(
                "/app/electric/getCoutomConfig", configFields
        ));

        JSONObject roomData = requireDataObject(post(
                "/app/electric/queryRoomSurplus", roomFields(config)
        ));
        BigDecimal configuredMinimum = decimal(ruleData, "customAmountBegin", "1");
        BigDecimal roomMinimum = decimal(roomData, "minAmount", "0");
        BigDecimal minimum = configuredMinimum.max(roomMinimum);
        BigDecimal maximum = decimal(ruleData, "customAmountEnd", "10000");
        if (maximum.compareTo(minimum) < 0) {
            throw new IOException("校付宝返回的充值金额范围无效");
        }

        List<String> suggestions = new ArrayList<>();
        String rawSuggestions = ruleData.optString("rechargeAmount", "");
        for (String item : rawSuggestions.split("\\|")) {
            try {
                BigDecimal amount = new BigDecimal(item.trim());
                if (amount.compareTo(minimum) >= 0 && amount.compareTo(maximum) <= 0) {
                    suggestions.add(formatAmount(amount));
                }
            } catch (NumberFormatException ignored) {
                // 单个推荐金额损坏不应影响用户手动输入，范围仍由 min/max 严格校验。
            }
        }

        String displayName = roomData.optString("displayRoomName", "").trim();
        if (displayName.isEmpty()) displayName = config.alias;
        return new RechargeContext(
                displayName,
                roomData.optDouble("surplus", 0.0),
                roomData.optDouble("amount", 0.0),
                minimum,
                maximum,
                suggestions,
                ruleData.optString("timeBegin", "00:30"),
                ruleData.optString("timeEnd", "23:30"),
                roomData.optString("thisSettlingTime", ""),
                // 校付宝接口约定 canBuy=0 才表示允许购买，与常见布尔语义相反。
                // 缺少 canBuy 时按“不允许”处理，支付入口必须 fail closed。
                roomData.optInt("canBuy", -1) == 0
        );
    }

    /**
     * 校验并规范化用户输入。使用 BigDecimal 避免 double 产生 0.1 精度误差。
     */
    public String normalizeAmount(String raw, RechargeContext context) {
        try {
            BigDecimal amount = new BigDecimal(raw == null ? "" : raw.trim());
            if (amount.scale() > 2) throw new IllegalArgumentException("充值金额最多保留两位小数");
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (amount.compareTo(context.minimumAmount) < 0
                    || amount.compareTo(context.maximumAmount) > 0) {
                throw new IllegalArgumentException(
                        "充值金额需在 " + formatAmount(context.minimumAmount)
                                + "～" + formatAmount(context.maximumAmount) + " 元之间"
                );
            }
            return formatAmount(amount);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("请输入正确的充值金额");
        }
    }

    /**
     * 创建一次充值订单并返回经过白名单校验的官方收银台地址。
     *
     * <p>顺序不能调整：先查待处理订单，再取一次性 token，最后下单。recharge 请求发出后
     * 任何 IOException 都按“结果未知”处理，调用方不得在后台自动重试。</p>
     */
    public RechargeCheckout createCheckout(
            AppConfig config, RechargeContext context, String normalizedAmount
    ) throws IOException, AuthExpiredException {
        JSONObject pendingRoot = post(
                "/app/electric/queryRoomIsRecharge", roomFields(config)
        );
        Object pending = pendingRoot.opt("data");
        if (Boolean.TRUE.equals(pending)) {
            throw new IOException("该房间已有正在处理的充值订单，请稍后再试");
        }

        JSONObject tokenRoot = post(
                "/center/common/token/get", new LinkedHashMap<>()
        );
        String token = tokenRoot.optString("data", "").trim();
        if (token.isEmpty()) throw new IOException("未取得一次性充值令牌");

        Map<String, String> fields = roomFields(config);
        fields.put("money", normalizedAmount);
        fields.put("submitToken", token);
        fields.put("platform", PLATFORM);
        // 抓包中 extJson 当前只包含空 serialNO；直接使用常量可避免写入任何用户标识。
        fields.put("extJson", "{\"serialNO\":\"\"}");
        if (context.thisSettlingTime != null && !context.thisSettlingTime.isEmpty()) {
            fields.put("thisSettlingTime", context.thisSettlingTime);
        }

        JSONObject root = post("/app/electric/recharge", fields);
        Object checkout = root.opt("data");
        if (!(checkout instanceof String)) {
            // 当前学校抓包返回字符串 URL。若未来改成 xmpch POST 表单，必须单独适配，
            // 不能把未知 JSON 对象拼成网址后盲目打开。
            throw new IOException("校付宝返回了暂不支持的收银台类型");
        }
        String checkoutUrl = RechargeCheckoutUrlValidator.requireTrusted(
                (String) checkout
        );
        return new RechargeCheckout(
                checkoutUrl,
                RechargeCheckoutUrlValidator.requirePaymentNo(checkoutUrl)
        );
    }

    /**
     * 查询“本次支付单号”的官方订单终态。
     *
     * <p>第一步从缴电费记录列表按 payNo 精确找到业务 orderNo；第二步调用官方详情接口
     * 读取 payStatus。列表查询只取最新 20 条，因为本次订单刚创建，不可能在两分钟检测
     * 窗口内被正常使用产生的 20 条更新记录挤出首页。</p>
     */
    public RechargeOrderStatus queryOrderStatus(
            AppConfig config, RechargeAttempt attempt
    ) throws IOException, AuthExpiredException {
        config.validate();
        if (attempt == null
                || attempt.paymentNo == null
                || !attempt.paymentNo.matches("\\d{10,40}")) {
            throw new IOException("本地充值尝试缺少有效的官方支付单号");
        }

        Map<String, String> listFields = new LinkedHashMap<>();
        listFields.put("pageSize", "20");
        listFields.put("subType", ELECTRIC_ORDER_SUBTYPE);
        listFields.put("currentPage", "1");
        listFields.put("platform", PLATFORM);
        JSONObject listRoot = post(
                "/app/order/bussisdw/queryListData", listFields
        );
        JSONObject listRow = findOrderByPaymentNo(listRoot, attempt.paymentNo);
        if (listRow == null) {
            // 新成功订单写入交易记录可能有短暂延迟；“没出现”绝不能直接当成失败。
            return RechargeOrderStatus.notFound(attempt.paymentNo);
        }

        String orderNo = listRow.optString("orderNo", "").trim();
        if (!orderNo.matches("\\d{10,40}")) {
            throw new IOException("校付宝缴费记录缺少有效业务订单号");
        }
        Map<String, String> detailFields = new LinkedHashMap<>();
        detailFields.put("orderNo", orderNo);
        detailFields.put("subType", ELECTRIC_ORDER_SUBTYPE);
        detailFields.put("platform", PLATFORM);
        JSONObject detail = requireDataObject(post(
                "/app/order/bussisdw/getDetailByOrderCode", detailFields
        ));
        return parseOfficialOrderDetail(
                detail,
                attempt.paymentNo,
                config.roomCode.trim(),
                attempt.requestedCents
        );
    }

    /**
     * 从官方交易记录中按 payNo 精确匹配，不使用金额、时间或列表第一项进行猜测。
     */
    static JSONObject findOrderByPaymentNo(JSONObject root, String paymentNo) {
        JSONArray rows = root == null ? null : root.optJSONArray("rows");
        if (rows == null || paymentNo == null) return null;
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row != null && paymentNo.equals(row.optString("payNo", ""))) {
                return row;
            }
        }
        return null;
    }

    /**
     * 将官方详情转为应用内部状态，并同时验证学校、房间、金额和支付单号。
     *
     * <p>这些字段任一不符都按协议异常处理，不能仅看到 payStatus=2 就把其他房间或其他
     * 金额的订单记入当前房间。官方前端当前明确使用：1 等待支付、2 支付成功、其他失败。</p>
     */
    static RechargeOrderStatus parseOfficialOrderDetail(
            JSONObject detail,
            String expectedPaymentNo,
            String expectedRoomCode,
            long expectedCents
    ) throws IOException {
        if (detail == null) throw new IOException("校付宝订单详情为空");
        String paymentNo = detail.optString("payNo", "").trim();
        String orderNo = detail.optString("orderNo", "").trim();
        String schoolCode = detail.optString("schoolCode", "").trim();
        String roomCode = detail.optString("roomCode", "").trim();
        String productName = detail.optString("prodName", "").trim();
        long totalCents = moneyCents(detail, "totalMoney");
        if (!expectedPaymentNo.equals(paymentNo)
                || !orderNo.matches("\\d{10,40}")
                || !AppConstants.SCHOOL_CODE.equals(schoolCode)
                || !expectedRoomCode.equals(roomCode)
                || !productName.contains("电")
                || totalCents != expectedCents) {
            throw new IOException("校付宝订单详情与本次房间或金额不一致");
        }

        int payStatus = detail.optInt("payStatus", Integer.MIN_VALUE);
        if (payStatus == Integer.MIN_VALUE) {
            // 字段缺失属于接口协议异常，不能把“未知”冒充成官方明确失败。
            throw new IOException("校付宝订单详情缺少支付状态");
        }
        int state = payStatus == 1
                ? RechargeOrderStatus.PENDING
                : payStatus == 2
                ? RechargeOrderStatus.SUCCESS
                : RechargeOrderStatus.FAILED;
        long paidAt = 0;
        if (state == RechargeOrderStatus.SUCCESS) {
            String payTime = detail.optString("payTime", "").trim();
            try {
                paidAt = LocalDateTime.parse(payTime, OFFICIAL_TIME_FORMAT)
                        .atZone(OFFICIAL_TIME_ZONE)
                        .toInstant()
                        .toEpochMilli();
            } catch (DateTimeParseException exception) {
                throw new IOException("校付宝成功订单缺少有效支付时间", exception);
            }
        }
        return RechargeOrderStatus.of(
                state, paymentNo, orderNo, totalCents, paidAt
        );
    }

    private static long moneyCents(JSONObject data, String key)
            throws IOException {
        Object raw = data.opt(key);
        try {
            return new BigDecimal(String.valueOf(raw))
                    .setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2)
                    .longValueExact();
        } catch (RuntimeException exception) {
            throw new IOException("校付宝订单金额格式无效", exception);
        }
    }

    private Map<String, String> roomFields(AppConfig config) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("areaId", AppConstants.AREA_ID);
        fields.put("buildingCode", config.buildingCode());
        fields.put("floorCode", config.floorCode());
        fields.put("roomCode", config.roomCode.trim());
        fields.put("platform", PLATFORM);
        return fields;
    }

    private JSONObject post(String path, Map<String, String> fields)
            throws IOException, AuthExpiredException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(BASE_URL + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(25_000);
        connection.setDoOutput(true);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty(
                "Content-Type", "application/x-www-form-urlencoded;charset=UTF-8"
        );
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        connection.setRequestProperty("Origin", BASE_URL);
        connection.setRequestProperty("Referer", BASE_URL + "/");
        connection.setRequestProperty("Cookie", AppConstants.SHIRO_COOKIE);
        connection.setRequestProperty(
                "User-Agent", "JiangliElectricity/" + BuildConfig.VERSION_NAME + " Android"
        );

        byte[] body = encode(fields).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(stream);
        connection.disconnect();
        if (status == 401 || status == 403 || (status >= 300 && status < 400)) {
            throw new AuthExpiredException("校付宝登录态已失效，暂时无法充值");
        }
        if (status < 200 || status >= 300) {
            throw new IOException("校付宝充值服务返回 HTTP " + status);
        }

        try {
            JSONObject root = new JSONObject(response);
            if (!root.optBoolean("success", false) || root.optInt("statusCode", -1) != 0) {
                String message = root.optString("message", "校付宝操作失败");
                if (message.contains("登录") || message.contains("授权")
                        || message.contains("会话")) {
                    throw new AuthExpiredException("校付宝登录态已失效，暂时无法充值");
                }
                throw new IOException(message);
            }
            return root;
        } catch (JSONException exception) {
            throw new IOException("校付宝返回内容无法解析", exception);
        }
    }

    private JSONObject requireDataObject(JSONObject root) throws IOException {
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new IOException("校付宝返回内容缺少 data");
        return data;
    }

    private BigDecimal decimal(JSONObject data, String key, String fallback) {
        String value = data.has(key) && !data.isNull(key)
                ? String.valueOf(data.opt(key)) : fallback;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return new BigDecimal(fallback);
        }
    }

    private String encode(Map<String, String> fields) throws IOException {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8.name()));
            result.append('=');
            result.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8.name()));
        }
        return result.toString();
    }

    private String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    static String formatAmount(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
