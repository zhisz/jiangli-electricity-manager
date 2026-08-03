package com.shangzhili.electricityreminder;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 后台远程消息统一执行器。代码顺序就是产品优先级：先检查并通知版本更新，再同步公告。
 * 两项都采用短超时和静默失败，任何服务器故障都不会影响余额监测。
 */
public final class RemoteMessageWorker extends Worker {
    private static final String TAG = "RemoteMessageWorker";
    private static final String WORK_NAME = "remote-update-and-announcement-check";

    public RemoteMessageWorker(
            @NonNull Context context, @NonNull WorkerParameters parameters
    ) {
        super(context, parameters);
    }

    static void enqueue(Context context) {
        Constraints network = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(RemoteMessageWorker.class)
                .setConstraints(network)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE, request
        );
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        String updateUrl = BuildConfig.UPDATE_MANIFEST_URL.trim();
        if (!updateUrl.isEmpty()) {
            try {
                UpdateInfo info = new AppUpdateClient().query(updateUrl);
                if (info != null && info.versionCode > BuildConfig.VERSION_CODE) {
                    new NotificationHelper(context).appUpdate(info);
                }
            } catch (Exception exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "后台更新检查静默失败", exception);
            }
        }

        CountDownLatch announcementFinished = new CountDownLatch(1);
        AnnouncementCenter.checkInBackground(context, announcementFinished::countDown);
        try {
            announcementFinished.await(12, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return Result.success();
    }
}
