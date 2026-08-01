package com.shangzhili.electricityreminder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 用户可修改的监测配置。
 *
 * <p>JID 和校区代码已经移到 {@link AppConstants}。楼栋、楼层代码本来就是完整房间代码的
 * 前缀，所以配置中只保留 roomCode，避免用户重复输入同一串数字。</p>
 */
public final class AppConfig {
    /**
     * 新房间的低余额复查默认间隔。
     *
     * <p>60 分钟能在提醒及时性与后台耗电之间取得更合理的平衡；该常量只用于创建
     * 新配置和配置字段缺失时的回退，不覆盖用户已经保存过的自定义间隔。</p>
     */
    public static final double DEFAULT_REPEAT_MINUTES = 60;

    /** 当前接口的房间码结构：9 位楼栋 + 3 位楼层 + 3 位房间，共 15 位。 */
    private static final int ROOM_CODE_LENGTH = 15;
    private static final int BUILDING_CODE_LENGTH = 9;
    private static final int FLOOR_CODE_LENGTH = 12;

    public final String alias;
    public final String roomCode;
    public final String metric;
    public final double threshold;
    public final double recoveryThreshold;
    /** 余额持续不足时，两次通知之间至少间隔多少分钟。 */
    public final double repeatMinutes;
    /**
     * 0.15.0 及以前的每日监测时间点。
     * 0.16.0 只用它取消升级前残留闹钟并保留降级兼容，实际调度固定为每个整点。
     */
    public final List<DailyCheckTime> checkTimes;
    // 保留首个时间的旧字段供 0.4.0 配置迁移代码使用；新调度逻辑统一读取 checkTimes。
    public final int checkHour;
    public final int checkMinute;

    public AppConfig(
            String alias,
            String roomCode,
            String metric,
            double threshold,
            double recoveryThreshold,
            double repeatMinutes,
            int checkHour,
            int checkMinute
    ) {
        this(alias, roomCode, metric, threshold, recoveryThreshold, repeatMinutes,
                Collections.singletonList(new DailyCheckTime(checkHour, checkMinute)));
    }

    public AppConfig(
            String alias,
            String roomCode,
            String metric,
            double threshold,
            double recoveryThreshold,
            double repeatMinutes,
            List<DailyCheckTime> checkTimes
    ) {
        this.alias = alias;
        this.roomCode = roomCode;
        this.metric = metric;
        this.threshold = threshold;
        this.recoveryThreshold = recoveryThreshold;
        this.repeatMinutes = repeatMinutes;
        List<DailyCheckTime> normalized = new ArrayList<>();
        if (checkTimes != null) {
            for (DailyCheckTime time : checkTimes) {
                if (time != null && !normalized.contains(time)) normalized.add(time);
            }
        }
        Collections.sort(normalized);
        this.checkTimes = Collections.unmodifiableList(normalized);
        DailyCheckTime first = normalized.isEmpty() ? new DailyCheckTime(9, 0) : normalized.get(0);
        this.checkHour = first.hour;
        this.checkMinute = first.minute;
    }

    /**
     * 从完整房间码推导楼栋码。
     * 例如 001001015005005 的前 9 位 001001015 就是 buildingCode。
     */
    public String buildingCode() {
        return roomCode.trim().substring(0, BUILDING_CODE_LENGTH);
    }

    /**
     * 从完整房间码推导楼层码。
     * 同一示例的前 12 位 001001015005 就是 floorCode。
     */
    public String floorCode() {
        return roomCode.trim().substring(0, FLOOR_CODE_LENGTH);
    }

    /** 在保存或发请求前集中校验，防止无效配置进入后台任务。 */
    public void validate() {
        String normalizedRoomCode = roomCode == null ? "" : roomCode.trim();
        if (!normalizedRoomCode.matches("\\d{" + ROOM_CODE_LENGTH + "}")) {
            throw new IllegalArgumentException("房间代码必须是 15 位数字");
        }
        if (!("amount".equals(metric) || "surplus".equals(metric))) {
            throw new IllegalArgumentException("提醒指标无效");
        }
        if (threshold < 0 || recoveryThreshold <= threshold) {
            throw new IllegalArgumentException("恢复阈值必须大于提醒阈值");
        }
        if (repeatMinutes < 1) {
            throw new IllegalArgumentException("重复提醒间隔至少为 1 分钟");
        }
        if (checkTimes.isEmpty()) {
            throw new IllegalArgumentException("旧版监测时间兼容数据不能为空");
        }
    }
}
