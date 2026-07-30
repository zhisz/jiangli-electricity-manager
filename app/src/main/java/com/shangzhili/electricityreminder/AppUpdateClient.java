package com.shangzhili.electricityreminder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 只负责通过 HTTPS 获取、校验并解析开发者发布的更新清单。 */
public final class AppUpdateClient {
    private static final int MAX_RESPONSE_CHARS = 64 * 1024;

    public UpdateInfo query(String manifestUrl) throws IOException {
        URL url = requireHttps(manifestUrl, "更新清单");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty(
                "User-Agent", "JiangliElectricity/" + BuildConfig.VERSION_NAME + " Android"
        );

        int status = connection.getResponseCode();
        // 即使静态托管平台发生重定向，最终地址也不允许降级为明文 HTTP。
        if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
            connection.disconnect();
            throw new IOException("更新清单重定向到了非 HTTPS 地址");
        }
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String body = readLimited(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IOException("更新服务返回 HTTP " + status);
        }
        return parse(body);
    }

    /** 保留为包内可见，便于不访问网络时对示例清单做解析测试。 */
    static UpdateInfo parse(String body) throws IOException {
        try {
            JSONObject root = new JSONObject(body);
            if (!root.optBoolean("enabled", true)) return null;

            int versionCode = root.getInt("versionCode");
            String versionName = root.getString("versionName").trim();
            int minimum = root.optInt("minSupportedVersionCode", 0);
            boolean forced = root.optBoolean("forceUpdate", false);
            String apkUrl = root.getString("apkUrl").trim();
            String sha256 = root.getString("sha256").trim().toLowerCase(Locale.ROOT);
            String notes = root.optString("releaseNotes", "本次更新包含功能改进和问题修复。")
                    .trim();

            if (versionCode <= 0 || versionName.isEmpty()) {
                throw new IOException("更新清单中的版本信息无效");
            }
            requireHttps(apkUrl, "APK 下载");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IOException("更新清单中的 SHA-256 无效");
            }
            return new UpdateInfo(
                    versionCode, versionName, Math.max(0, minimum), forced,
                    apkUrl, sha256, notes
            );
        } catch (JSONException exception) {
            throw new IOException("更新清单 JSON 格式错误", exception);
        }
    }

    private static URL requireHttps(String rawUrl, String label) throws IOException {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new IOException(label + "地址为空");
        }
        URL url = new URL(rawUrl.trim());
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException(label + "地址必须使用 HTTPS");
        }
        return url;
    }

    private static String readLimited(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            char[] buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (result.length() + count > MAX_RESPONSE_CHARS) {
                    throw new IOException("更新清单内容过大");
                }
                result.append(buffer, 0, count);
            }
        }
        return result.toString();
    }
}
