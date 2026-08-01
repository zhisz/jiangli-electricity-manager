package com.shangzhili.electricityreminder;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * 应用级前后台生命周期协调器。
 *
 * <p>这里使用系统 ActivityLifecycleCallbacks 实现与 ProcessLifecycleOwner 等价的进程级
 * 语义：页面切换时新 Activity 会先 started，计数不会归零；只有所有页面都停止超过
 * 700ms 才视为真正进入后台。因而旋转、详情页跳转不会重复检查版本或增加日活。</p>
 */
public final class ElecApplication extends Application
        implements Application.ActivityLifecycleCallbacks {
    private static final long BACKGROUND_DEBOUNCE_MILLIS = 700L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int startedActivities;
    private boolean inForeground;
    private boolean foregroundUsageHandled;
    private boolean foregroundUpdateHandled;
    private Activity managerActivity;
    private AppUpdateManager updateManager;
    private final Runnable confirmBackground = () -> {
        if (startedActivities == 0) endForegroundSession();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        // 持久化低频版本检查，普通划掉后台后仍可由系统在合适时机发送新版本通知。
        UpdateNotificationWorker.schedulePeriodic(this);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivities++;
        mainHandler.removeCallbacks(confirmBackground);
        if (!inForeground) {
            inForeground = true;
            foregroundUsageHandled = false;
            foregroundUpdateHandled = false;
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        ensureUpdateManager(activity);
        updateManager.onResume();
        if (!foregroundUsageHandled) {
            foregroundUsageHandled = true;
            UsageReporter.reportForeground(this);
        }
        requestUpdateWhenSafe(activity);
    }

    /**
     * 首次使用引导和支付过渡页都不应被更新弹窗覆盖。MainActivity 会在引导关闭后再次调用，
     * 因而该次前台会话仍会检查一次，而不是永久跳过。
     */
    void requestUpdateWhenSafe(Activity activity) {
        if (foregroundUpdateHandled || activity instanceof RechargePaymentActivity) return;
        if (activity instanceof MainActivity
                && ((MainActivity) activity).isFirstUseGuideShowing()) return;
        ensureUpdateManager(activity);
        foregroundUpdateHandled = true;
        updateManager.checkOnLaunch();
    }

    /** 基地里的显式“检查更新”不受同一次前台会话自动检查去重限制。 */
    void requestManualUpdate(Activity activity) {
        ensureUpdateManager(activity);
        updateManager.checkOnLaunch();
    }

    @Override
    public void onActivityStopped(Activity activity) {
        startedActivities = Math.max(0, startedActivities - 1);
        if (startedActivities == 0) {
            if (activity.isFinishing() && !activity.isChangingConfigurations()) {
                /*
                 * finishAffinity（例如强制更新弹窗的“退出应用”）不是普通页面切换。
                 * 必须立即结束前台会话；否则用户在 700ms 内重新点图标时，会沿用
                 * foregroundUpdateHandled=true，错误跳过下一次强制更新检查。
                 */
                endForegroundSession();
            } else {
                // 短延迟只用于覆盖配置变更与正常 Activity 交接。
                mainHandler.postDelayed(confirmBackground, BACKGROUND_DEBOUNCE_MILLIS);
            }
        }
    }

    /**
     * 更新流程在调用 finishAffinity 前显式通知 Application。即使厂商系统延迟分发
     * onActivityStopped，下一次点击桌面图标也会被识别为全新的前台会话。
     */
    void endForegroundSessionForExplicitExit() {
        mainHandler.removeCallbacks(confirmBackground);
        endForegroundSession();
    }

    private void endForegroundSession() {
        inForeground = false;
        foregroundUsageHandled = false;
        foregroundUpdateHandled = false;
        destroyUpdateManager();
    }

    private void ensureUpdateManager(Activity activity) {
        if (managerActivity == activity && updateManager != null) return;
        destroyUpdateManager();
        managerActivity = activity;
        updateManager = new AppUpdateManager(activity);
    }

    private void destroyUpdateManager() {
        if (updateManager != null) updateManager.destroy();
        updateManager = null;
        managerActivity = null;
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
