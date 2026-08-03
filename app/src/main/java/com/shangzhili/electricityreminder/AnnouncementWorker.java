package com.shangzhili.electricityreminder;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

/**
 * 公告低频后台检查。WorkManager 会把请求与系统联网窗口合并，不启动常驻服务；30 分钟是
 * 期望周期而非实时承诺，厂商省电策略可能延后执行，但不会额外占用持续后台资源。
 */
public final class AnnouncementWorker extends Worker {
    private static final String WORK_NAME = "developer-announcement-sync";

    public AnnouncementWorker(
            @NonNull Context context, @NonNull WorkerParameters workerParameters
    ) {
        super(context, workerParameters);
    }

    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                AnnouncementWorker.class, 30, TimeUnit.MINUTES, 10, TimeUnit.MINUTES
        ).setConstraints(constraints).build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        );
    }

    @NonNull @Override public Result doWork() {
        CountDownLatch completed = new CountDownLatch(1);
        AnnouncementCenter.checkInBackground(getApplicationContext(), completed::countDown);
        try {
            // 网络层自身只有数秒超时；额外上限防止系统工作线程被异常情况长期占用。
            completed.await(12, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return Result.success();
    }
}
