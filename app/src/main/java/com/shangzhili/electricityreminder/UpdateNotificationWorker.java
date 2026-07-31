package com.shangzhili.electricityreminder;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * 后台监测成功后异步检查新版本，仅负责系统通知，不负责弹窗或下载。
 *
 * <p>工作名包含上海自然日，因此一天最多创建一次；即便 WorkManager 重复投递，
 * NotificationHelper 还会按目标 versionCode 二次幂等。任何网络错误都返回 success，
 * 避免更新服务器故障触发指数重试并干扰核心监测任务。</p>
 */
public final class UpdateNotificationWorker extends Worker {
    private static final String TAG = "UpdateNotifyWorker";
    private static final String PERIODIC_WORK_NAME = "periodic-app-update-notification";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    public UpdateNotificationWorker(
            @NonNull Context context,
            @NonNull WorkerParameters workerParameters
    ) {
        super(context, workerParameters);
    }

    public static void enqueue(Context context) {
        String workName = "app-update-notification-"
                + LocalDate.now(SHANGHAI);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                UpdateNotificationWorker.class
        ).setConstraints(constraints).build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                workName, ExistingWorkPolicy.KEEP, request
        );
    }

    /**
     * 注册低频持久任务。12 小时周期、2 小时弹性窗口允许 Android 将请求与其他联网任务
     * 合并，避免常驻服务、频繁唤醒和额外耗电；普通划掉最近任务不会取消 WorkManager。
     *
     * <p>UPDATE 只更新约束和周期，不会因为每次打开 App 而叠加多个后台任务。系统设置中
     * 的“强行停止”会冻结整个应用的 JobScheduler，这是 Android 无法绕过的安全规则。</p>
     */
    public static void schedulePeriodic(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                UpdateNotificationWorker.class,
                12, TimeUnit.HOURS,
                2, TimeUnit.HOURS
        ).setConstraints(constraints).build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        );
    }

    @NonNull
    @Override
    public Result doWork() {
        String url = BuildConfig.UPDATE_MANIFEST_URL.trim();
        if (url.isEmpty()) return Result.success();
        try {
            UpdateInfo info = new AppUpdateClient().query(url);
            if (info != null && info.versionCode > BuildConfig.VERSION_CODE) {
                new NotificationHelper(getApplicationContext()).appUpdate(info);
            }
        } catch (IOException | RuntimeException exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "后台更新检查静默失败", exception);
        }
        return Result.success();
    }
}
