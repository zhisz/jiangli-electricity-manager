package com.shangzhili.electricityreminder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 读取开发者服务器上的公共房间历史。
 *
 * <p>请求只携带房间码和增量起点，不上传房间备注、提醒阈值、充值记录或其他用户配置。
 * 连接与读取超时都刻意较短；调用者必须把失败当作“没有补充数据”，不能影响本地功能。</p>
 */
public final class CloudHistoryClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int READ_TIMEOUT_MILLIS = 4_000;
    private static final int PAGE_LIMIT = 500;

    public List<CloudHistoryRecord> fetch(
            String serviceBaseUrl,
            String roomCode,
            long sinceMillis
    ) throws IOException {
        if (serviceBaseUrl == null || serviceBaseUrl.trim().isEmpty()) {
            return new ArrayList<>();
        }
        if (roomCode == null || !roomCode.matches("\\d{15}")) {
            throw new IllegalArgumentException("云端历史请求的房间码无效");
        }

        StringBuilder endpoint = new StringBuilder(withoutTrailingSlash(serviceBaseUrl))
                .append("/api/v1/public-history?roomCode=")
                .append(urlEncode(roomCode))
                .append("&limit=").append(PAGE_LIMIT);
        if (sinceMillis > 0) {
            endpoint.append("&sinceMillis=").append(Math.max(0, sinceMillis));
        }

        HttpURLConnection connection = (HttpURLConnection)
                new URL(endpoint.toString()).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "gzip");
        connection.setRequestProperty("User-Agent", "JiangliElectricity/0.22 Android");

        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("云端历史接口返回 HTTP " + status);
            }
            InputStream stream = connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                stream = new GZIPInputStream(stream);
            }
            return parse(readAll(stream), roomCode);
        } finally {
            connection.disconnect();
        }
    }

    /** 严格解析成功记录；单条损坏数据会被忽略，不让整批云端数据污染本地曲线。 */
    static List<CloudHistoryRecord> parse(String response, String expectedRoomCode)
            throws IOException {
        try {
            JSONObject root = new JSONObject(response);
            if (root.optInt("dataVersion", 0) < 1
                    || !"Asia/Shanghai".equals(root.optString("timezone"))) {
                throw new IOException("云端历史版本或时区无效");
            }
            JSONArray records = root.optJSONArray("records");
            if (records == null) throw new IOException("云端历史缺少 records");
            List<CloudHistoryRecord> result = new ArrayList<>();
            for (int index = 0; index < records.length(); index++) {
                JSONObject item = records.optJSONObject(index);
                if (item == null) continue;
                String roomCode = item.optString("roomCode", "");
                long timestamp = parseTimestamp(item.optString("queriedAt", ""));
                CloudHistoryRecord record = new CloudHistoryRecord(
                        item.optString("sampleKey", ""),
                        roomCode,
                        timestamp,
                        item.optDouble("balanceKwh", Double.NaN),
                        item.optDouble("amountYuan", Double.NaN),
                        item.optString("queryResult", "")
                );
                // 服务端即使误返回其他房间，也不能跨房间写入本地历史。
                if (expectedRoomCode.equals(roomCode) && record.isValidSuccess()) {
                    result.add(record);
                }
            }
            return result;
        } catch (JSONException | RuntimeException exception) {
            throw new IOException("云端历史返回格式无效", exception);
        }
    }

    private static long parseTimestamp(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String withoutTrailingSlash(String value) {
        String trimmed = value.trim();
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') end--;
        return trimmed.substring(0, end);
    }
}
