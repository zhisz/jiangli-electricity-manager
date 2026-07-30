package com.shangzhili.electricityreminder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ElectricityClient {
    private static final String QUERY_URL =
            "https://application.xiaofubao.com/app/electric/queryRoomSurplus";

    public Reading query(AppConfig config) throws IOException, AuthExpiredException {
        // 先校验 roomCode，确保下面截取楼栋/楼层前缀时绝不会发生越界。
        config.validate();
        HttpURLConnection connection = (HttpURLConnection) new URL(QUERY_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setDoOutput(true);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        connection.setRequestProperty("Origin", "https://application.xiaofubao.com");
        connection.setRequestProperty("Referer", "https://application.xiaofubao.com/");
        connection.setRequestProperty("Cookie", AppConstants.SHIRO_COOKIE);
        connection.setRequestProperty("User-Agent", "ElectricityReminder/0.1 Android");

        Map<String, String> fields = new LinkedHashMap<>();
        // 接口虽然要求四个字段，但 buildingCode 和 floorCode 都是 roomCode 的前缀。
        // 页面只收集完整房间码，这里在真正发请求前自动补齐接口所需字段。
        fields.put("areaId", AppConstants.AREA_ID);
        fields.put("buildingCode", config.buildingCode());
        fields.put("floorCode", config.floorCode());
        fields.put("roomCode", config.roomCode.trim());
        fields.put("platform", "WECHAT_H5");
        byte[] body = encode(fields).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        int status = connection.getResponseCode();
        if (status == 401 || status == 403 || (status >= 300 && status < 400)) {
            connection.disconnect();
            throw new AuthExpiredException("登录态已失效，请更新 shiroJID");
        }
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IOException("查询接口返回 HTTP " + status);
        }

        try {
            JSONObject root = new JSONObject(response);
            if (!root.optBoolean("success", false) || root.optInt("statusCode", -1) != 0) {
                String message = root.optString("message", "查询失败");
                if (message.contains("登录") || message.contains("授权") || message.contains("会话")) {
                    throw new AuthExpiredException("登录态已失效，请更新 shiroJID");
                }
                throw new IOException(message);
            }
            JSONObject data = root.getJSONObject("data");
            if (!data.has("surplus") || !data.has("amount")) {
                throw new IOException("接口返回内容缺少余额字段");
            }
            return new Reading(data.getDouble("surplus"), data.getDouble("amount"), System.currentTimeMillis());
        } catch (JSONException exception) {
            throw new AuthExpiredException("未收到有效数据，登录态可能已失效");
        }
    }

    private String encode(Map<String, String> fields) {
        StringBuilder result = new StringBuilder();
        try {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                if (result.length() > 0) result.append('&');
                result.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
                result.append('=');
                result.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
        return result.toString();
    }

    private String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
