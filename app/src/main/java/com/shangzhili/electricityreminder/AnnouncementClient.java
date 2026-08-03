package com.shangzhili.electricityreminder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 公告接口的纯网络层：短超时、无重试风暴，失败由上层静默回退到本地待读队列。 */
final class AnnouncementClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 2_500;
    private static final int READ_TIMEOUT_MILLIS = 4_000;

    static final class SyncResult {
        final List<Announcement> announcements;
        final List<Long> withdrawnIds;

        SyncResult(List<Announcement> announcements, List<Long> withdrawnIds) {
            this.announcements = announcements;
            this.withdrawnIds = withdrawnIds;
        }
    }

    SyncResult sync(String baseUrl, String installId, long afterId) throws IOException {
        JSONObject request = new JSONObject();
        try {
            request.put("installId", installId).put("afterId", afterId);
            JSONObject response = request(baseUrl, "/api/v1/announcements/sync", request, false);
            JSONArray rows = response.optJSONArray("announcements");
            List<Announcement> result = new ArrayList<>();
            if (rows != null) for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row == null) continue;
                long id = row.optLong("id", 0);
                String title = row.optString("title", "").trim();
                String content = row.optString("content", "").trim();
                if (id > 0 && !title.isEmpty() && !content.isEmpty()) {
                    result.add(new Announcement(
                            id, title, content, row.optString("published_at", "")
                    ));
                }
            }
            JSONArray withdrawn = response.optJSONArray("withdrawnIds");
            List<Long> withdrawnIds = new ArrayList<>();
            if (withdrawn != null) for (int index = 0; index < withdrawn.length(); index++) {
                long id = withdrawn.optLong(index, 0);
                if (id > 0) withdrawnIds.add(id);
            }
            return new SyncResult(result, withdrawnIds);
        } catch (org.json.JSONException exception) {
            throw new IOException("公告响应格式无效", exception);
        }
    }

    void markRead(String baseUrl, String installId, long announcementId) throws IOException {
        JSONObject request = new JSONObject();
        try {
            request.put("installId", installId).put("announcementId", announcementId);
            request(baseUrl, "/api/v1/announcements/read", request, true);
        } catch (org.json.JSONException exception) {
            throw new IOException("公告回执格式无效", exception);
        }
    }

    private JSONObject request(
            String baseUrl, String path, JSONObject payload, boolean allowEmpty
    ) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL endpoint = new URL(withoutTrailingSlash(baseUrl) + path);
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("公告服务返回状态 " + status);
            }
            if (allowEmpty && status == HttpURLConnection.HTTP_NO_CONTENT) {
                return new JSONObject();
            }
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                return new JSONObject(body.toString());
            }
        } catch (org.json.JSONException exception) {
            throw new IOException("公告服务响应不是有效 JSON", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String withoutTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }
}
