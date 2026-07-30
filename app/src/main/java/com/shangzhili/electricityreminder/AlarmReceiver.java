package com.shangzhili.electricityreminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** 接收某个房间的整点闹钟，并把实际联网查询交给 WorkManager。 */
public final class AlarmReceiver extends BroadcastReceiver {
    public static final String ACTION_DAILY_CHECK =
            "com.shangzhili.electricityreminder.action.DAILY_CHECK";
    public static final String EXTRA_ROOM_ID = "roomId";
    public static final String EXTRA_TIME_KEY = "timeKey";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_DAILY_CHECK.equals(intent.getAction())) return;
        String roomId = intent.getStringExtra(EXTRA_ROOM_ID);
        if (roomId == null || roomId.isEmpty()) return;

        RoomRepository repository = new RoomRepository(context);
        if (!repository.isConfigured(roomId)
                || !repository.isMonitoringEnabled(roomId)) return;
        String timeKey = intent.getStringExtra(EXTRA_TIME_KEY);
        // 升级后可能有一个旧版 HH:mm 广播已进入系统队列；只接受新的稳定整点键，
        // 避免旧任务在整点任务之外额外查询一次。
        if (!Scheduler.HOURLY_TIME_KEY.equals(timeKey)) return;
        // 先预约下一整点，即使后续 WorkManager 因断网延后，本房间的时钟链也不会中断。
        Scheduler.scheduleNextHourly(context, roomId);
        enqueueCheck(context, roomId, ElectricityWorker.MODE_DAILY, workName(roomId, timeKey));
    }

    static void enqueueCheck(
            Context context, String roomId, String mode, String uniqueWorkName
    ) {
        Constraints networkRequired = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        Data input = new Data.Builder()
                .putString(ElectricityWorker.KEY_MODE, mode)
                .putString(EXTRA_ROOM_ID, roomId)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ElectricityWorker.class)
                .setInputData(input)
                .setConstraints(networkRequired)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName, ExistingWorkPolicy.REPLACE, request
        );
    }

    static String workName(String roomId, String timeKey) {
        return "daily_electricity_check_" + roomId + "_" + timeKey.replace(':', '_');
    }

    static String legacyWorkName(String roomId) {
        return "daily_electricity_check_" + roomId;
    }
}
