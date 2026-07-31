package com.shangzhili.electricityreminder;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;

/** 真正执行联网查询，并根据“整点采样”或“余额不足复查”模式处理结果。 */
public final class ElectricityWorker extends Worker {
    public static final String KEY_MODE = "checkMode";
    public static final String MODE_DAILY = "daily";
    public static final String MODE_REPEAT = "repeat";

    public ElectricityWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        boolean repeatMode = MODE_REPEAT.equals(getInputData().getString(KEY_MODE));
        String roomId = getInputData().getString(AlarmReceiver.EXTRA_ROOM_ID);
        if (roomId == null || roomId.isEmpty()) return Result.failure();
        RoomRepository repository = new RoomRepository(context);

        // 后台任务可能在用户清除数据后才被系统唤醒；此时没有有效配置，结束重复循环。
        if (!repository.isConfigured(roomId)
                || !repository.isMonitoringEnabled(roomId)) {
            if (repeatMode) Scheduler.cancelRepeatAlarm(context, roomId);
            // 用户主动暂停监测不是任务错误，不需要 WorkManager 重试。
            return Result.success();
        }

        AppConfig config = repository.load(roomId);
        MonitorState state = new MonitorState(context, roomId);
        NotificationHelper notifications = new NotificationHelper(context);
        try {
            Reading reading = new ElectricityClient().query(config);
            state.recordSuccess(reading);
            new ReadingHistoryStore(context).record(
                    roomId, reading, repeatMode ? MODE_REPEAT : MODE_DAILY
            );
            // 监测任务成功即表示当天真实使用了 App 服务；匿名心跳与房间数据完全隔离。
            UsageReporter.reportMonitoringSucceeded(context);
            // 后台整点监测也代表应用仍在使用。独立排队一次轻量更新检查，服务器不可用
            // 时静默结束，绝不改变本次电费查询、历史写入和低余额提醒的成功结果。
            UpdateNotificationWorker.enqueue(context);

            if (repeatMode) {
                handleRepeatResult(context, roomId, config, state, notifications, reading);
            } else {
                handleDailyResult(context, roomId, config, state, notifications, reading);
            }
            return Result.success();
        } catch (AuthExpiredException exception) {
            // 凭据失效后继续按分钟请求没有意义，还会造成无效接口访问，因此终止重复循环。
            if (repeatMode) Scheduler.cancelRepeatAlarm(context, roomId);
            int failures = state.recordFailure(exception.getMessage());
            if (failures == 1 || state.shouldSendFailureAlert(failures)) {
                notifications.authExpired(roomId, config.alias);
                state.markFailureAlertSent();
            }
            return Result.failure();
        } catch (IOException | RuntimeException exception) {
            int failures = state.recordFailure(safeMessage(exception));
            if (state.shouldSendFailureAlert(failures)) {
                notifications.monitorFailure(roomId, config.alias, safeMessage(exception));
                state.markFailureAlertSent();
            }

            // 重复闹钟已经提前预约了下一轮，不再让 WorkManager 自己重试，否则会形成两套
            // 并行查询。整点任务没有这种后续复查闹钟，因此保留最多两次指数退避重试。
            if (repeatMode) return Result.failure();
            return getRunAttemptCount() < 2 ? Result.retry() : Result.failure();
        }
    }

    /**
     * 整点查询只使用较低的“提醒阈值”启动告警。
     * 一旦启动，立即通知并预约 repeatMinutes 分钟后的复查。
     *
     * <p>整点查询频率提高后，不能每小时重复发送一次“首次不足”通知；已经处于告警循环时，
     * 整点任务只负责补充趋势采样，真正的重复通知仍完全服从用户设置的复查间隔。</p>
     */
    private void handleDailyResult(
            Context context,
            String roomId,
            AppConfig config,
            MonitorState state,
            NotificationHelper notifications,
        Reading reading
    ) {
        if (state.isBelowAlertThreshold(config, reading)) {
            if (!state.isLowAlertActive()) {
                notifications.lowBalance(roomId, config, reading);
                state.markLowAlertSent();
                Scheduler.scheduleRepeat(context, roomId, config.repeatMinutes);
            }
        } else if (!state.isBelowRecoveryThreshold(config, reading)) {
            // 达到恢复阈值时解除旧状态并停止可能残留的重复闹钟。
            state.updateRecovery(config, reading);
            Scheduler.cancelRepeatAlarm(context, roomId);
        }
        // 处于“提醒阈值 ≤ 当前值 < 恢复阈值”时维持现状：
        // 已启动的循环继续运行，尚未启动的循环也不会仅因迟滞区间而被创建。
    }

    /**
     * 重复复查使用较高的“恢复阈值”作为停止线。
     * 低于恢复阈值说明还没有真正恢复，再次通知；达到恢复阈值则结束整个循环。
     */
    private void handleRepeatResult(
            Context context,
            String roomId,
            AppConfig config,
            MonitorState state,
            NotificationHelper notifications,
            Reading reading
    ) {
        if (state.isBelowRecoveryThreshold(config, reading)) {
            notifications.stillLowBalance(roomId, config, reading);
            state.markLowAlertSent();
        } else {
            state.updateRecovery(config, reading);
            Scheduler.cancelRepeatAlarm(context, roomId);
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "未知错误" : message;
    }
}
