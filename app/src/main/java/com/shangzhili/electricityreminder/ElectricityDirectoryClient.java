package com.shangzhili.electricityreminder;

import org.json.JSONArray;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从校付宝读取江西理工大学南昌校区的楼栋、楼层和房间目录。
 *
 * <p>三个接口都是只读 POST 请求，并且逐级依赖上一级返回的代码：buildingCode 用于查询楼层，
 * buildingCode + floorCode 用于查询房间。最终得到的 roomCode 仍由现有 SecureStore 加密保存。</p>
 */
public final class ElectricityDirectoryClient {
    private static final String BASE_URL = "https://application.xiaofubao.com/app/electric/";

    public List<DirectoryOption> queryBuildings() throws IOException, AuthExpiredException {
        Map<String, String> fields = baseFields();
        return queryOptions("queryBuilding", fields, "buildingCode", "buildingName");
    }

    public List<DirectoryOption> queryFloors(String buildingCode)
            throws IOException, AuthExpiredException {
        Map<String, String> fields = baseFields();
        fields.put("buildingCode", buildingCode);
        return queryOptions("queryFloor", fields, "floorCode", "floorName");
    }

    public List<DirectoryOption> queryRooms(String buildingCode, String floorCode)
            throws IOException, AuthExpiredException {
        Map<String, String> fields = baseFields();
        fields.put("buildingCode", buildingCode);
        fields.put("floorCode", floorCode);
        return queryOptions("queryRoom", fields, "roomCode", "roomName");
    }

    private Map<String, String> baseFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("areaId", AppConstants.AREA_ID);
        fields.put("platform", "WECHAT_H5");
        return fields;
    }

    /** 执行三级目录共用的 HTTP 和 JSON 解析逻辑。 */
    private List<DirectoryOption> queryOptions(
            String endpoint,
            Map<String, String> fields,
            String codeField,
            String nameField
    ) throws IOException, AuthExpiredException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(BASE_URL + endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setDoOutput(true);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty(
                "Content-Type", "application/x-www-form-urlencoded;charset=UTF-8"
        );
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        connection.setRequestProperty("Origin", "https://application.xiaofubao.com");
        connection.setRequestProperty("Referer", "https://application.xiaofubao.com/");
        connection.setRequestProperty("Cookie", AppConstants.SHIRO_COOKIE);
        connection.setRequestProperty("User-Agent", "ElectricityReminder/0.7 Android");

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
            throw new IOException("房间目录接口返回 HTTP " + status);
        }

        try {
            JSONObject root = new JSONObject(response);
            if (!root.optBoolean("success", false) || root.optInt("statusCode", -1) != 0) {
                String message = root.optString("message", "房间目录加载失败");
                if (message.contains("登录") || message.contains("授权") || message.contains("会话")) {
                    throw new AuthExpiredException("登录态已失效，请更新 shiroJID");
                }
                throw new IOException(message);
            }

            JSONArray rows = root.optJSONArray("rows");
            if (rows == null) throw new IOException("房间目录返回内容缺少 rows");
            List<DirectoryOption> result = new ArrayList<>();
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row == null) continue;
                String code = row.optString(codeField, "").trim();
                String name = row.optString(nameField, "").trim();
                // 忽略缺少代码或名称的脏数据，避免用户选到无法继续请求的项目。
                if (!code.isEmpty() && !name.isEmpty()) {
                    result.add(new DirectoryOption(code, name));
                }
            }
            if (result.isEmpty()) throw new IOException("当前选择下没有可用项目");
            return result;
        } catch (JSONException exception) {
            throw new IOException("房间目录返回格式无法解析", exception);
        }
    }

    private String encode(Map<String, String> fields) {
        StringBuilder result = new StringBuilder();
        try {
            // 使用 Android 26 已支持的 String 编码重载；Charset 重载只在较新系统存在。
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
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
