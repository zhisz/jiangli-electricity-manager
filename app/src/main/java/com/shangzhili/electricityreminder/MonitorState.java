package com.shangzhili.electricityreminder;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/** 保存单个房间的运行状态；不同 roomId 使用互相隔离的 SharedPreferences。 */
public final class MonitorState {
    private static final String LEGACY_PREFS = "monitor_state";
    private static final String PREFIX = "monitor_state_";
    private final SharedPreferences preferences;

    public MonitorState(Context context, String roomId) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFIX + roomId, Context.MODE_PRIVATE
        );
    }

    public void recordSuccess(Reading reading) {
        preferences.edit()
                .putLong("lastSuccessAt", reading.timestamp)
                .putString("lastSurplus", Double.toString(reading.surplus))
                .putString("lastAmount", Double.toString(reading.amount))
                .putInt("failureCount", 0)
                .remove("lastError")
                .apply();
    }

    public int recordFailure(String message) {
        int failures = preferences.getInt("failureCount", 0) + 1;
        preferences.edit()
                .putInt("failureCount", failures)
                .putLong("lastFailureAt", System.currentTimeMillis())
                .putString("lastError", message)
                .apply();
        return failures;
    }

    public boolean isBelowAlertThreshold(AppConfig config, Reading reading) {
        return monitoredValue(config, reading) < config.threshold;
    }

    public boolean isBelowRecoveryThreshold(AppConfig config, Reading reading) {
        return monitoredValue(config, reading) < config.recoveryThreshold;
    }

    public boolean isLowAlertActive() {
        return preferences.getBoolean("activeLow", false);
    }

    public void markLowAlertSent() {
        preferences.edit().putBoolean("activeLow", true)
                .putLong("lastLowAlertAt", System.currentTimeMillis()).apply();
    }

    public void resetLowAlertState() {
        preferences.edit()
                .putBoolean("activeLow", false)
                .remove("lastLowAlertAt")
                .remove("recoveredAt")
                .apply();
    }

    public void updateRecovery(AppConfig config, Reading reading) {
        double value = monitoredValue(config, reading);
        if (preferences.getBoolean("activeLow", false) && value >= config.recoveryThreshold) {
            preferences.edit().putBoolean("activeLow", false)
                    .putLong("recoveredAt", System.currentTimeMillis()).apply();
        }
    }

    public boolean shouldSendFailureAlert(int failures) {
        long last = preferences.getLong("lastFailureAlertAt", 0);
        return failures >= 3 && System.currentTimeMillis() - last >= 86_400_000L;
    }

    public void markFailureAlertSent() {
        preferences.edit().putLong("lastFailureAlertAt", System.currentTimeMillis()).apply();
    }

    public long getLastSuccessAt() {
        return preferences.getLong("lastSuccessAt", 0);
    }

    public double getLastSurplus() {
        return readDouble("lastSurplus");
    }

    public double getLastAmount() {
        return readDouble("lastAmount");
    }

    public String getLastError() {
        return preferences.getString("lastError", "");
    }

    public void clear() {
        preferences.edit().clear().apply();
    }

    /** 把旧版全局状态复制给迁移生成的第一个房间。 */
    public static void migrateLegacy(Context context, String roomId) {
        SharedPreferences oldPreferences = context.getSharedPreferences(
                LEGACY_PREFS, Context.MODE_PRIVATE
        );
        SharedPreferences newPreferences = context.getSharedPreferences(
                PREFIX + roomId, Context.MODE_PRIVATE
        );
        if (!newPreferences.getAll().isEmpty()) return;

        SharedPreferences.Editor editor = newPreferences.edit();
        for (Map.Entry<String, ?> entry : oldPreferences.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
        }
        editor.apply();
    }

    private double monitoredValue(AppConfig config, Reading reading) {
        return config.metric.equals("amount") ? reading.amount : reading.surplus;
    }

    private double readDouble(String key) {
        try {
            return Double.parseDouble(preferences.getString(key, "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
