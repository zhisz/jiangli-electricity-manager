package com.shangzhili.electricityreminder;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 多房间配置仓库。
 *
 * <p>每个房间由稳定的 roomId 标识；别名、阈值和时间保存在普通 SharedPreferences，
 * 具体 roomCode 仍通过 SecureStore 加密。roomId 不使用房间号本身，避免把住址信息暴露在
 * 闹钟 Intent、WorkManager 名称或数据库索引中。</p>
 */
public final class RoomRepository {
    private static final String PREFS_NAME = "room_repository";
    private static final String KEY_ROOM_IDS = "roomIds";
    private static final String KEY_MIGRATED = "legacyMigrated";
    private static final String LEGACY_ROOM_ID = "legacy-room";

    private final Context context;
    private final SharedPreferences preferences;
    private final SecureStore secureStore;

    public RoomRepository(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        secureStore = new SecureStore(this.context);
        migrateLegacyConfigOnce();
    }

    public String createRoomId() {
        return "room-" + UUID.randomUUID();
    }

    public List<String> listRoomIds() {
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_ROOM_IDS, "[]"));
            for (int index = 0; index < array.length(); index++) {
                String roomId = array.optString(index, "");
                if (!roomId.isEmpty()) result.add(roomId);
            }
        } catch (JSONException ignored) {
            // 配置列表损坏时返回空列表；各房间的独立配置不会因此被错误解析或覆盖。
        }
        return result;
    }

    public boolean contains(String roomId) {
        return listRoomIds().contains(roomId);
    }

    /**
     * 把指定房间移动到新的列表下标，并立即持久化首页顺序。
     * 排序只修改 roomIds 数组，不会复制或重写任何房间配置、历史数据和闹钟。
     */
    public void moveRoomToIndex(String draggedRoomId, int targetIndex) {
        List<String> roomIds = listRoomIds();
        if (!roomIds.remove(draggedRoomId)) return;
        int safeIndex = Math.max(0, Math.min(targetIndex, roomIds.size()));
        roomIds.add(safeIndex, draggedRoomId);
        saveRoomIds(roomIds);
    }

    public AppConfig load(String roomId) {
        return new AppConfig(
                preferences.getString(key(roomId, "alias"), "新房间"),
                secureStore.get(key(roomId, "roomCode")),
                preferences.getString(key(roomId, "metric"), "amount"),
                readDouble(key(roomId, "threshold"), 20),
                readDouble(key(roomId, "recoveryThreshold"), 25),
                // 仅当字段确实缺失或损坏时使用 60 分钟；已保存的用户设置原样保留。
                readDouble(key(roomId, "repeatMinutes"), AppConfig.DEFAULT_REPEAT_MINUTES),
                readCheckTimes(roomId)
        );
    }

    public void save(String roomId, AppConfig config) {
        config.validate();
        boolean existingRoom = contains(roomId);
        saveInternal(roomId, config);
        List<String> roomIds = listRoomIds();
        if (!roomIds.contains(roomId)) {
            roomIds.add(roomId);
            saveRoomIds(roomIds);
        }
        // 新房间仅因“立即查询”而保存时不应自动启用后台任务；只有详情页的
        // “保存并启用监测”会显式调用 setMonitoringEnabled(true)。
        if (!existingRoom) {
            preferences.edit().putBoolean(key(roomId, "monitoringEnabled"), false).apply();
        }
    }

    public boolean isConfigured(String roomId) {
        if (!contains(roomId)) return false;
        try {
            load(roomId).validate();
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * 0.5.0 已存在的房间没有这个字段，默认视为已启用以保持升级行为；
     * 0.5.1 新建房间会在首次保存时显式写入 false。
     */
    public boolean isMonitoringEnabled(String roomId) {
        return preferences.getBoolean(key(roomId, "monitoringEnabled"), contains(roomId));
    }

    public void setMonitoringEnabled(String roomId, boolean enabled) {
        if (!contains(roomId)) return;
        preferences.edit().putBoolean(key(roomId, "monitoringEnabled"), enabled).apply();
    }

    /**
     * 只更新房间别名，不改动阈值、整点监测、房间码和后台启用状态。
     * 详情页的“小保存按钮”调用此方法，避免为了改名称而意外重置告警或重排闹钟。
     */
    public String updateAlias(String roomId, String alias) {
        if (!contains(roomId)) throw new IllegalArgumentException("请先保存房间配置");
        String normalized = normalizedAlias(alias);
        preferences.edit().putString(key(roomId, "alias"), normalized).apply();
        return normalized;
    }

    public void delete(String roomId) {
        Scheduler.cancelAllForRoom(context, roomId);
        List<String> roomIds = listRoomIds();
        roomIds.remove(roomId);
        saveRoomIds(roomIds);

        SharedPreferences.Editor editor = preferences.edit();
        for (String field : new String[]{
                "alias", "metric", "threshold", "recoveryThreshold",
                "repeatMinutes", "checkTimes", "checkHour", "checkMinute", "monitoringEnabled"
        }) {
            editor.remove(key(roomId, field));
        }
        editor.apply();
        secureStore.remove(key(roomId, "roomCode"));
        new MonitorState(context, roomId).clear();
        new ReadingHistoryStore(context).deleteRoom(roomId);
    }

    private void migrateLegacyConfigOnce() {
        if (preferences.getBoolean(KEY_MIGRATED, false)) return;

        // 0.4.0 及以前只有一套 ConfigStore。若它有效，将其复制成首页中的第一个房间。
        ConfigStore legacyStore = new ConfigStore(context);
        if (legacyStore.isConfigured()) {
            saveInternal(LEGACY_ROOM_ID, legacyStore.load());
            List<String> ids = new ArrayList<>();
            ids.add(LEGACY_ROOM_ID);
            saveRoomIds(ids);
            preferences.edit()
                    .putBoolean(key(LEGACY_ROOM_ID, "monitoringEnabled"), true)
                    .apply();
            MonitorState.migrateLegacy(context, LEGACY_ROOM_ID);
        }
        preferences.edit().putBoolean(KEY_MIGRATED, true).apply();
    }

    private void saveInternal(String roomId, AppConfig config) {
        secureStore.put(key(roomId, "roomCode"), config.roomCode.trim());
        preferences.edit()
                .putString(key(roomId, "alias"), normalizedAlias(config.alias))
                .putString(key(roomId, "metric"), config.metric)
                .putString(key(roomId, "threshold"), Double.toString(config.threshold))
                .putString(key(roomId, "recoveryThreshold"), Double.toString(config.recoveryThreshold))
                .putString(key(roomId, "repeatMinutes"), Double.toString(config.repeatMinutes))
                .putString(key(roomId, "checkTimes"), checkTimesJson(config.checkTimes))
                // 同时保留首个时间到旧字段，便于降级安装旧版本时仍有一个可用计划。
                .putInt(key(roomId, "checkHour"), config.checkHour)
                .putInt(key(roomId, "checkMinute"), config.checkMinute)
                .apply();
    }

    /** 新版本优先读取时间数组；旧房间没有该字段时自动把原来的单个时间迁移为一项。 */
    private List<DailyCheckTime> readCheckTimes(String roomId) {
        List<DailyCheckTime> result = new ArrayList<>();
        String saved = preferences.getString(key(roomId, "checkTimes"), "");
        if (saved != null && !saved.isEmpty()) {
            try {
                JSONArray array = new JSONArray(saved);
                for (int index = 0; index < array.length(); index++) {
                    String[] parts = array.optString(index, "").split(":", -1);
                    if (parts.length != 2) continue;
                    try {
                        DailyCheckTime time = new DailyCheckTime(
                                Integer.parseInt(parts[0]), Integer.parseInt(parts[1])
                        );
                        if (!result.contains(time)) result.add(time);
                    } catch (IllegalArgumentException ignored) {
                        // 单项损坏不影响同一房间其他有效时间；全损坏时仍会回退到旧字段。
                    }
                }
            } catch (JSONException ignored) {
                // JSON 损坏时使用旧版单时间字段，确保后台监测不会完全丢失。
            }
        }
        if (result.isEmpty()) {
            result.add(new DailyCheckTime(
                    preferences.getInt(key(roomId, "checkHour"), 9),
                    preferences.getInt(key(roomId, "checkMinute"), 0)
            ));
        }
        return result;
    }

    private String checkTimesJson(List<DailyCheckTime> checkTimes) {
        JSONArray array = new JSONArray();
        for (DailyCheckTime time : checkTimes) array.put(time.key());
        return array.toString();
    }

    private void saveRoomIds(List<String> roomIds) {
        JSONArray array = new JSONArray();
        for (String roomId : roomIds) array.put(roomId);
        preferences.edit().putString(KEY_ROOM_IDS, array.toString()).apply();
    }

    private double readDouble(String key, double fallback) {
        try {
            return Double.parseDouble(preferences.getString(key, Double.toString(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String normalizedAlias(String alias) {
        return alias == null || alias.trim().isEmpty() ? "未命名房间" : alias.trim();
    }

    private String key(String roomId, String field) {
        return roomId + "." + field;
    }
}
