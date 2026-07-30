package com.shangzhili.electricityreminder;

import android.content.Context;
import android.content.SharedPreferences;

/** 负责在手机本地读取和保存用户可修改的监测参数。 */
public final class ConfigStore {
    private final SecureStore secureStore;
    private final SharedPreferences preferences;

    public ConfigStore(Context context) {
        Context appContext = context.getApplicationContext();
        secureStore = new SecureStore(appContext);
        preferences = appContext.getSharedPreferences("app_config", Context.MODE_PRIVATE);
    }

    public AppConfig load() {
        return new AppConfig(
                preferences.getString("alias", "家里"),
                secureStore.get("roomCode"),
                preferences.getString("metric", "amount"),
                readDouble("threshold", 20),
                readDouble("recoveryThreshold", 25),
                readRepeatMinutes(),
                preferences.getInt("checkHour", 9),
                // 老版本只保存小时。升级后没有 checkMinute 时使用 0，旧配置会自然迁移为 HH:00。
                preferences.getInt("checkMinute", 0)
        );
    }

    public void save(AppConfig config) {
        config.validate();

        // roomCode 含有具体住址信息，仍使用 Android Keystore 加密保存。
        secureStore.put("roomCode", config.roomCode.trim());
        preferences.edit()
                .putString("alias", config.alias.trim().isEmpty() ? "家里" : config.alias.trim())
                .putString("metric", config.metric)
                .putString("threshold", Double.toString(config.threshold))
                .putString("recoveryThreshold", Double.toString(config.recoveryThreshold))
                .putString("repeatMinutes", Double.toString(config.repeatMinutes))
                // 升级到分钟制后删除旧键，避免后续读取时产生歧义。
                .remove("repeatHours")
                .putInt("checkHour", config.checkHour)
                .putInt("checkMinute", config.checkMinute)
                .apply();
    }

    public boolean isConfigured() {
        try {
            load().validate();
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private double readDouble(String key, double fallback) {
        try {
            return Double.parseDouble(preferences.getString(key, Double.toString(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /**
     * 兼容 0.2.0 及更早版本：旧配置存的是小时，所以首次升级读取时乘以 60。
     * 保存一次新配置后只保留 repeatMinutes，之后不会重复换算。
     */
    private double readRepeatMinutes() {
        if (preferences.contains("repeatMinutes")) {
            return readDouble("repeatMinutes", 2_880);
        }
        if (preferences.contains("repeatHours")) {
            return readDouble("repeatHours", 48) * 60;
        }
        // 原来的默认值是 48 小时，换算为 2880 分钟，保持新安装的提醒频率不突变。
        return 2_880;
    }
}
