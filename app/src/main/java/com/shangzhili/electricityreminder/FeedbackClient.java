package com.shangzhili.electricityreminder;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 向开发者服务器提交署名反馈；失败只返回给当前页面，不影响任何本地功能。 */
final class FeedbackClient {
    void submit(String signature, String content) throws Exception {
        String base = BuildConfig.ELEC_SERVICE_BASE_URL == null
                ? "" : BuildConfig.ELEC_SERVICE_BASE_URL.trim();
        if (!base.startsWith("https://")) throw new IOException("反馈服务暂不可用");
        URL url = new URL(base.replaceAll("/+$", "") + "/api/v1/feedback");
        JSONObject body = new JSONObject()
                .put("signature", signature)
                .put("content", content)
                .put("appVersion", BuildConfig.VERSION_NAME);
        byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(12_000);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(encoded.length);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        try (OutputStream stream = connection.getOutputStream()) {
            stream.write(encoded);
        }
        int status = connection.getResponseCode();
        connection.disconnect();
        if (status == 429) throw new IOException("提交过于频繁，请稍后再试");
        if (status < 200 || status >= 300) throw new IOException("反馈提交失败");
    }
}
