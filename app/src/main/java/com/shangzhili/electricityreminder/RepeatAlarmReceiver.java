package com.shangzhili.electricityreminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 低余额状态下，按该房间自己的分钟间隔发起新一次联网查询。 */
public final class RepeatAlarmReceiver extends BroadcastReceiver {
    public static final String ACTION_REPEAT_CHECK =
            "com.shangzhili.electricityreminder.action.REPEAT_CHECK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_REPEAT_CHECK.equals(intent.getAction())) return;
        String roomId = intent.getStringExtra(AlarmReceiver.EXTRA_ROOM_ID);
        if (roomId == null || roomId.isEmpty()) return;

        RoomRepository repository = new RoomRepository(context);
        MonitorState state = new MonitorState(context, roomId);
        if (!repository.isConfigured(roomId)
                || !repository.isMonitoringEnabled(roomId)
                || !state.isLowAlertActive()) {
            Scheduler.cancelRepeat(context, roomId);
            return;
        }
        AppConfig config = repository.load(roomId);

        Scheduler.scheduleRepeat(context, roomId, config.repeatMinutes);
        AlarmReceiver.enqueueCheck(
                context, roomId, ElectricityWorker.MODE_REPEAT, workName(roomId)
        );
    }

    static String workName(String roomId) {
        return "repeat_electricity_check_" + roomId;
    }
}
