package com.shangzhili.electricityreminder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 向开发者服务器发送最小化、匿名的应用启动统计。
 *
 * <p>这里刻意不读取也不发送房间码、余额、提醒规则、充值记录、设备型号、手机号等信息。
 * Android 8+ 上会将系统按“设备用户 + App 签名”隔离的 ANDROID_ID 与包名先做 SHA-256，
 * 服务器再用独立密钥做 HMAC。这样覆盖安装或同签名重装仍会归为同一部手机，而服务器和
 * 数据库都看不到原始 ANDROID_ID。下载行为单独统计，绝不会被算作实际用户或日活。</p>
 *
 * <p>上报完全运行在独立线程，并设置很短的连接超时。服务器离线、DNS 失败或返回错误时
 * 只在调试版本写日志，不弹窗、不重试、不阻塞主线程，也不会影响查电费和本地提醒。</p>
 */
public final class UsageReporter {
    private static final String TAG = "UsageReporter";
    private static final String PREFS = "anonymous_usage_preferences";
    private static final String KEY_INSTALLATION_ID = "installationId";
    private static final String KEY_LAST_SUCCESSFUL_DAY = "lastSuccessfulDay";
    private static final String KEY_LAST_SUCCESSFUL_VERSION = "lastSuccessfulVersionCode";
    private static final String KEY_PENDING_DAY = "pendingDay";
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int READ_TIMEOUT_MILLIS = 3_000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean REPORT_IN_PROGRESS = new AtomicBoolean(false);

    private UsageReporter() {
    }

    /**
     * 返回本次安装的随机匿名 ID，仅在极少数无法取得 ANDROID_ID 的设备上作为兜底。
     * 同步提交用于确保更新下载紧接着发生时也能取得同一 ID。
     */
    public static String installationId(Context context) {
        SharedPreferences preferences = preferences(context);
        String existing = preferences.getString(KEY_INSTALLATION_ID, "");
        // 不使用 Java 11 的 String.isBlank()，确保 Android 8（API 26）运行时兼容。
        if (existing != null && !existing.trim().isEmpty()) return existing;

        String generated = UUID.randomUUID().toString();
        // apply() 会先同步更新内存、再异步落盘，紧接着的下载请求可以读到相同 ID，
        // 同时避免在主线程等待磁盘写入。
        preferences.edit().putString(KEY_INSTALLATION_ID, generated).apply();
        return generated;
    }

    /**
     * 返回同一手机、同一 App 签名下稳定的匿名摘要。
     *
     * <p>Android 8+ 的 ANDROID_ID 对“设备用户 + App 签名”隔离，同一签名版本覆盖安装或
     * 卸载重装后通常保持一致。客户端先把它和包名一起做 SHA-256，服务器只能看到 64 位
     * 摘要，看不到系统原值。恢复出厂设置、切换手机用户或更换签名会被视为新设备。</p>
     */
    @SuppressLint("HardwareIds")
    public static String deviceIdentity(Context context) {
        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID
        );
        String source = androidId == null || androidId.trim().isEmpty()
                ? "fallback:" + installationId(context)
                : "android:" + context.getPackageName() + ":" + androidId;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // 所有 Android 版本都必须提供 SHA-256；保留随机 ID 仅作为理论上的安全兜底。
            return installationId(context);
        }
    }

    /**
     * 每个上海时区自然日最多成功上报一次。失败时不记录日期，因此用户当天再次正常打开
     * App 时仍有一次自然补报机会，但单次打开不会连续重试消耗流量。
     */
    public static void reportAppOpened(Context context) {
        reportForeground(context);
    }

    /**
     * 每次应用真正进入前台时调用。相同上海自然日成功过就不再联网；失败日期会保存在本地，
     * 后续前台会话有限补发。服务端不会把过期补发计入“今天”，因此断网恢复不会污染日活。
     */
    public static void reportForeground(Context context) {
        report(context, "foreground");
    }

    /** 后台整点监测成功也代表当天真实使用了 App 能力，按同一设备、同一天幂等计入日活。 */
    public static void reportMonitoringSucceeded(Context context) {
        report(context, "monitor");
    }

    private static void report(Context context, String source) {
        String baseUrl = BuildConfig.ELEC_SERVICE_BASE_URL.trim();
        if (baseUrl.isEmpty()) return;

        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = preferences(appContext);
        // 服务端也以中国标准时间聚合日活，避免用户临时切换手机时区导致同日重复上报。
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        int lastVersion = preferences.getInt(KEY_LAST_SUCCESSFUL_VERSION, 0);
        // 同一天普通重复打开只计一次；但升级到新 versionCode 后必须立即补报，
        // 否则开发者后台的“最新版已安装”要等到第二天才会更新。
        if (today.equals(preferences.getString(KEY_LAST_SUCCESSFUL_DAY, ""))
                && lastVersion == BuildConfig.VERSION_CODE) return;
        if (!REPORT_IN_PROGRESS.compareAndSet(false, true)) return;
        String pendingDay = preferences.getString(KEY_PENDING_DAY, "");
        String reportingDay = pendingDay == null || pendingDay.isEmpty() ? today : pendingDay;
        if (pendingDay == null || pendingDay.isEmpty()) {
            preferences.edit().putString(KEY_PENDING_DAY, today).apply();
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            boolean sendCurrentDayNext = false;
            try {
                JSONObject body = new JSONObject()
                        .put("installId", deviceIdentity(appContext))
                        .put("versionCode", BuildConfig.VERSION_CODE)
                        .put("versionName", BuildConfig.VERSION_NAME)
                        // eventDay 只用于让服务器识别过期补发；最终计数日期仍由服务器上海时间决定。
                        .put("eventDay", reportingDay)
                        .put("historical", !today.equals(reportingDay))
                        .put("source", source);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

                URL endpoint = new URL(withoutTrailingSlash(baseUrl) + "/api/v1/heartbeat");
                connection = (HttpURLConnection) endpoint.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
                connection.setReadTimeout(READ_TIMEOUT_MILLIS);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }

                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    if (today.equals(reportingDay)) {
                        preferences.edit()
                                .putString(KEY_LAST_SUCCESSFUL_DAY, today)
                                .putInt(KEY_LAST_SUCCESSFUL_VERSION, BuildConfig.VERSION_CODE)
                                .remove(KEY_PENDING_DAY)
                                .apply();
                    } else {
                        // 先把旧事件交给服务器判定为过期且不计入今天，再发送本次真实前台事件。
                        preferences.edit().remove(KEY_PENDING_DAY).apply();
                        sendCurrentDayNext = true;
                    }
                } else if (BuildConfig.DEBUG) {
                    Log.w(TAG, "匿名启动统计返回非成功状态：" + status);
                }
            } catch (Exception exception) {
                // 遥测属于附加能力。任何异常都必须在此吞掉，不能影响用户核心功能。
                if (BuildConfig.DEBUG) Log.w(TAG, "匿名启动统计发送失败", exception);
            } finally {
                if (connection != null) connection.disconnect();
                REPORT_IN_PROGRESS.set(false);
                if (sendCurrentDayNext) report(appContext, source);
            }
        });
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String withoutTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }
}
