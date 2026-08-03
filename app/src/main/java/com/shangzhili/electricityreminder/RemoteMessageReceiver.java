package com.shangzhili.electricityreminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 闹钟入口只负责续约下一次检查并提交 Worker，不在主线程直接联网。 */
public final class RemoteMessageReceiver extends BroadcastReceiver {
    public static final String ACTION_CHECK_REMOTE_MESSAGES =
            "com.zhisz.electricityreminder.action.CHECK_REMOTE_MESSAGES";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_CHECK_REMOTE_MESSAGES.equals(intent.getAction())) return;
        RemoteMessageScheduler.scheduleNext(context);
        RemoteMessageWorker.enqueue(context);
    }
}
