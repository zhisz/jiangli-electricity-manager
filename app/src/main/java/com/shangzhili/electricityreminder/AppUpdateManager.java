package com.shangzhili.electricityreminder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页使用的应用更新协调器。
 *
 * <p>职责包括：启动时读取远程清单、按照开发者策略显示弹窗、交给系统 DownloadManager
 * 下载、校验 APK 的 SHA-256，并调用系统安装器。它不接触房间、电费、提醒或历史数据。</p>
 */
public final class AppUpdateManager {
    private static final String TAG = "AppUpdateManager";
    private static final String PREFS = "app_update_preferences";
    private static final String KEY_SKIPPED_VERSION = "skippedVersionCode";
    private static final String KEY_DOWNLOAD_ID = "downloadId";
    private static final String KEY_TARGET_CODE = "targetVersionCode";
    private static final String KEY_TARGET_NAME = "targetVersionName";
    private static final String KEY_TARGET_URL = "targetApkUrl";
    private static final String KEY_TARGET_SHA = "targetSha256";
    private static final String KEY_TARGET_NOTES = "targetReleaseNotes";
    private static final String KEY_TARGET_MANDATORY = "targetMandatory";
    private static final String KEY_TARGET_VERIFIED = "targetVerified";
    private static final String KEY_CACHED_FORCE_CODE = "cachedForceVersionCode";
    private static final String KEY_CACHED_FORCE_NAME = "cachedForceVersionName";
    private static final String KEY_CACHED_FORCE_URL = "cachedForceApkUrl";
    private static final String KEY_CACHED_FORCE_SHA = "cachedForceSha256";
    private static final String KEY_CACHED_FORCE_NOTES = "cachedForceReleaseNotes";
    private static final long NO_DOWNLOAD = -1L;

    private final Activity activity;
    private final DownloadManager downloadManager;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean checkStarted;
    /** 远程清单成功或失败后才允许处理本地 APK，防止旧缓存抢先弹出安装界面。 */
    private boolean remoteCheckResolved;
    private boolean verificationInProgress;
    private boolean receiverRegistered;
    private boolean skipResumeAfterOptionalInstaller;
    private AlertDialog blockingDialog;
    private AlertDialog readyDialog;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, NO_DOWNLOAD);
            if (completedId == pendingDownloadId() && remoteCheckResolved) {
                handlePendingDownload();
            }
        }
    };

    public AppUpdateManager(Activity activity) {
        this.activity = activity;
        this.downloadManager = (DownloadManager)
                activity.getSystemService(Context.DOWNLOAD_SERVICE);
        this.preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        registerDownloadReceiver();
    }

    /**
     * 每次前台会话先查询远程最新清单，再处理本地缓存或已下载 APK。
     *
     * <p>旧实现会先展示缓存的强制版本。例如设备缓存了 1.2.0，而服务器已经发布
     * 1.2.2，用户就会被迫先安装 1.2.0 再升级。现在远程清单拥有最高优先级；只有查询
     * 失败时才回退到本地缓存，既能直接跨版本升级，也保留断网时的强制策略。</p>
     */
    public void checkOnLaunch() {
        if (checkStarted) return;
        checkStarted = true;
        UpdateInfo cachedMandatory = cachedMandatoryInfo();
        String manifestUrl = BuildConfig.UPDATE_MANIFEST_URL.trim();
        if (manifestUrl.isEmpty()) {
            remoteCheckResolved = true;
            resumeCachedOrPending(cachedMandatory);
            return;
        }

        executor.execute(() -> {
            try {
                UpdateInfo info = new AppUpdateClient().query(manifestUrl);
                runOnMain(() -> resolveRemoteInfo(info, cachedMandatory));
            } catch (IOException exception) {
                // 启动检查不弹网络错误；断网不应影响用户查看余额和已有数据。
                if (BuildConfig.DEBUG) Log.w(TAG, "启动更新检查失败", exception);
                runOnMain(() -> {
                    remoteCheckResolved = true;
                    resumeCachedOrPending(cachedMandatory);
                });
            }
        });
    }

    /**
     * Activity 恢复时只清理已经满足的状态。未完成下载必须等本轮远程清单解析完毕后处理，
     * 否则一个已经下载好的旧版本会在网络请求返回前抢先弹出系统安装器。
     */
    public void onResume() {
        if (skipResumeAfterOptionalInstaller) {
            skipResumeAfterOptionalInstaller = false;
            return;
        }
        clearSatisfiedMandatoryCache();
        if (BuildConfig.VERSION_CODE >= preferences.getInt(KEY_TARGET_CODE, Integer.MAX_VALUE)) {
            clearPendingDownload();
            return;
        }
    }

    private void resolveRemoteInfo(UpdateInfo info, UpdateInfo cachedMandatory) {
        remoteCheckResolved = true;
        if (info == null || info.versionCode <= BuildConfig.VERSION_CODE) {
            // 清单明确没有更高版本时仍接续同版本的未完成安装；网络失败才使用旧强制缓存。
            if (pendingDownloadId() != NO_DOWNLOAD) handlePendingDownload();
            return;
        }
        boolean mandatory = info.isMandatoryFor(BuildConfig.VERSION_CODE);
        if (mandatory) saveCachedMandatory(info);

        int pendingCode = pendingVersionCode();
        if (shouldReplacePending(pendingCode, info.versionCode)) {
            discardPendingDownload();
        } else if (pendingDownloadId() != NO_DOWNLOAD) {
            // 本地任务已经是远程最新版，无需再次下载或显示第二个更新弹窗。
            handlePendingDownload();
            return;
        }
        if (!mandatory && skippedVersionCode() == info.versionCode) return;
        new NotificationHelper(activity).appUpdate(info);
        showUpdateDialog(info, mandatory);
    }

    private void resumeCachedOrPending(UpdateInfo cachedMandatory) {
        if (cachedMandatory != null
                && shouldReplacePending(
                pendingVersionCode(), cachedMandatory.versionCode
        )) {
            // 即使当前离线，只要上一次已缓存了更高版本，也不能继续安装更旧的 APK。
            discardPendingDownload();
        }
        if (pendingDownloadId() != NO_DOWNLOAD) {
            handlePendingDownload();
        } else if (cachedMandatory != null
                && cachedMandatory.versionCode > BuildConfig.VERSION_CODE) {
            showUpdateDialog(cachedMandatory, true);
        }
    }

    /** 包内可见，便于 JVM 回归“跨多个版本必须直接替换旧下载”的判断。 */
    static boolean shouldReplacePending(int pendingCode, int latestCode) {
        return pendingCode > 0 && latestCode > pendingCode;
    }

    public void destroy() {
        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(downloadReceiver);
            } catch (IllegalArgumentException ignored) {
                // Activity 已由系统回收时，重复注销不应造成二次崩溃。
            }
            receiverRegistered = false;
        }
        executor.shutdownNow();
    }

    private void showUpdateDialog(UpdateInfo info, boolean mandatory) {
        if (!activityUsable()) return;
        String policy = mandatory
                ? "\n\n此版本由开发者设为必须更新，完成更新后才能继续使用。"
                : "\n\n你可以稍后更新，或跳过本版本。";
        String message = (info.releaseNotes.isEmpty() ? "本次包含功能改进和问题修复。"
                : info.releaseNotes) + policy;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + info.versionName)
                .setMessage(message)
                .setPositiveButton("立即更新", (dialog, which) -> beginDownload(info, mandatory));
        if (mandatory) {
            builder.setNegativeButton("退出应用", (dialog, which) -> exitApplication());
        } else {
            builder.setNegativeButton("稍后", null)
                    .setNeutralButton("跳过此版本", (dialog, which) -> preferences.edit()
                            .putInt(KEY_SKIPPED_VERSION, info.versionCode).apply());
        }
        AlertDialog dialog = builder.create();
        dialog.setCancelable(!mandatory);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void beginDownload(UpdateInfo info, boolean mandatory) {
        if (pendingDownloadId() != NO_DOWNLOAD) {
            if (shouldReplacePending(pendingVersionCode(), info.versionCode)) {
                discardPendingDownload();
            } else {
                handlePendingDownload();
                return;
            }
        }
        try {
            Uri apkUri = Uri.parse(info.apkUrl);
            if (!"https".equalsIgnoreCase(apkUri.getScheme())) {
                throw new IllegalArgumentException("APK 下载地址必须使用 HTTPS");
            }
            String fileName = String.format(
                    Locale.ROOT, "jiangli-electricity-%d-%d.apk",
                    info.versionCode, System.currentTimeMillis()
            );
            DownloadManager.Request request = new DownloadManager.Request(apkUri)
                    .setTitle("江理电小侠 " + info.versionName)
                    .setDescription("正在下载应用更新")
                    .setMimeType("application/vnd.android.package-archive")
                    // 下载指标使用与启动心跳一致的匿名设备摘要做去重；服务器看不到
                    // Android ID 原值，且下载记录与实际用户/日活表完全分开。
                    .addRequestHeader(
                            "X-Elec-Install-ID",
                            UsageReporter.deviceIdentity(activity)
                    )
                    .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(
                            activity, Environment.DIRECTORY_DOWNLOADS, fileName
                    );
            long id = downloadManager.enqueue(request);
            savePendingDownload(id, info, mandatory);
            if (mandatory) showDownloadingDialog(info.versionName);
            else toast("更新已开始下载，完成后将提示安装");
        } catch (RuntimeException exception) {
            showDownloadFailure(info, mandatory, "无法开始下载：" + safeMessage(exception));
        }
    }

    private void handlePendingDownload() {
        long id = pendingDownloadId();
        if (id == NO_DOWNLOAD || downloadManager == null) return;
        try (Cursor cursor = downloadManager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                failPending("系统中找不到更新下载任务");
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                dismissBlockingDialog();
                if (preferences.getBoolean(KEY_TARGET_VERIFIED, false)) {
                    showReadyToInstall();
                } else {
                    verifyDownloadedApk(id);
                }
            } else if (status == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                failPending("更新下载失败（代码 " + reason + "）");
            } else if (isPendingMandatory()) {
                showDownloadingDialog(pendingVersionName());
            }
        } catch (RuntimeException exception) {
            failPending("读取更新下载状态失败：" + safeMessage(exception));
        }
    }

    private void verifyDownloadedApk(long id) {
        if (verificationInProgress) return;
        verificationInProgress = true;
        executor.execute(() -> {
            String error = null;
            try {
                Uri uri = downloadManager.getUriForDownloadedFile(id);
                if (uri == null) throw new IOException("无法读取已下载的 APK");
                String actual = sha256(uri);
                String expected = preferences.getString(KEY_TARGET_SHA, "");
                if (!actual.equalsIgnoreCase(expected)) {
                    throw new IOException("APK 完整性校验失败，请勿安装此文件");
                }
            } catch (IOException | NoSuchAlgorithmException exception) {
                error = safeMessage(exception);
            }
            String finalError = error;
            runOnMain(() -> {
                verificationInProgress = false;
                if (finalError == null) {
                    preferences.edit().putBoolean(KEY_TARGET_VERIFIED, true).apply();
                    showReadyToInstall();
                } else {
                    failPending(finalError);
                }
            });
        });
    }

    private String sha256(Uri uri) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法打开已下载的 APK");
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value));
        return hex.toString();
    }

    private void showReadyToInstall() {
        if (!activityUsable() || (readyDialog != null && readyDialog.isShowing())) return;
        long id = pendingDownloadId();
        Uri uri = downloadManager.getUriForDownloadedFile(id);
        if (uri == null) {
            failPending("无法取得已下载 APK 的安装地址");
            return;
        }
        boolean mandatory = isPendingMandatory();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("更新已下载")
                .setMessage("版本 " + pendingVersionName() + " 已完成下载和安全校验。")
                .setPositiveButton("安装更新", (dialog, which) -> requestInstall(uri, mandatory));
        if (mandatory) {
            builder.setNegativeButton("退出应用", (dialog, which) -> exitApplication());
        } else {
            builder.setNegativeButton("稍后", null);
        }
        readyDialog = builder.create();
        readyDialog.setCancelable(!mandatory);
        readyDialog.setCanceledOnTouchOutside(false);
        readyDialog.setOnDismissListener(dialog -> readyDialog = null);
        readyDialog.show();
    }

    private void requestInstall(Uri apkUri, boolean mandatory) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            showInstallPermissionDialog(mandatory);
            return;
        }
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            // 可选更新取消系统安装器后，本次回到 App 不立刻再次打扰；下次启动仍可继续安装。
            skipResumeAfterOptionalInstaller = !mandatory;
            activity.startActivity(install);
        } catch (RuntimeException exception) {
            skipResumeAfterOptionalInstaller = false;
            showInstallError("无法打开系统安装器：" + safeMessage(exception), mandatory);
        }
    }

    private void showInstallPermissionDialog(boolean mandatory) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("允许安装应用更新")
                .setMessage("Android 需要先允许江理电小侠安装此来源的更新。授权后会再次提示安装。")
                .setPositiveButton("前往授权", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(Uri.parse("package:" + activity.getPackageName()));
                    try {
                        activity.startActivity(intent);
                    } catch (RuntimeException exception) {
                        showInstallError("无法打开安装权限设置", mandatory);
                    }
                });
        if (mandatory) {
            builder.setNegativeButton("退出应用", (dialog, which) -> exitApplication());
        } else {
            builder.setNegativeButton("取消", null);
        }
        AlertDialog dialog = builder.create();
        dialog.setCancelable(!mandatory);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void showDownloadingDialog(String versionName) {
        if (!activityUsable() || (blockingDialog != null && blockingDialog.isShowing())) return;
        blockingDialog = new AlertDialog.Builder(activity)
                .setTitle("正在下载必要更新")
                .setMessage("版本 " + versionName + " 正在后台下载，请稍候。")
                .setNegativeButton("退出应用", (dialog, which) -> exitApplication())
                .create();
        blockingDialog.setCancelable(false);
        blockingDialog.setCanceledOnTouchOutside(false);
        blockingDialog.show();
    }

    private void failPending(String message) {
        UpdateInfo info = pendingInfo();
        boolean mandatory = isPendingMandatory();
        long id = pendingDownloadId();
        if (id != NO_DOWNLOAD) downloadManager.remove(id);
        clearPendingDownload();
        dismissBlockingDialog();
        showDownloadFailure(info, mandatory, message);
    }

    private void showDownloadFailure(UpdateInfo info, boolean mandatory, String message) {
        if (!activityUsable()) return;
        if (!mandatory) {
            toast(message);
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("必要更新未完成")
                .setMessage(message)
                .setPositiveButton("重新下载", (ignored, which) -> beginDownload(info, true))
                .setNegativeButton("退出应用", (ignored, which) -> exitApplication())
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void showInstallError(String message, boolean mandatory) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("无法安装更新")
                .setMessage(message);
        if (mandatory) {
            builder.setPositiveButton("重新尝试", (dialog, which) -> activity.getWindow()
                            .getDecorView().post(this::handlePendingDownload))
                    .setNegativeButton("退出应用", (dialog, which) -> exitApplication());
        } else {
            builder.setPositiveButton("知道了", null);
        }
        AlertDialog dialog = builder.create();
        dialog.setCancelable(!mandatory);
        dialog.show();
    }

    private void savePendingDownload(long id, UpdateInfo info, boolean mandatory) {
        preferences.edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putInt(KEY_TARGET_CODE, info.versionCode)
                .putString(KEY_TARGET_NAME, info.versionName)
                .putString(KEY_TARGET_URL, info.apkUrl)
                .putString(KEY_TARGET_SHA, info.sha256)
                .putString(KEY_TARGET_NOTES, info.releaseNotes)
                .putBoolean(KEY_TARGET_MANDATORY, mandatory)
                .putBoolean(KEY_TARGET_VERIFIED, false)
                .apply();
    }

    private UpdateInfo pendingInfo() {
        return new UpdateInfo(
                preferences.getInt(KEY_TARGET_CODE, 0),
                pendingVersionName(), 0, isPendingMandatory(),
                preferences.getString(KEY_TARGET_URL, ""),
                preferences.getString(KEY_TARGET_SHA, ""),
                preferences.getString(KEY_TARGET_NOTES, "")
        );
    }

    private void clearPendingDownload() {
        preferences.edit()
                .remove(KEY_DOWNLOAD_ID)
                .remove(KEY_TARGET_CODE)
                .remove(KEY_TARGET_NAME)
                .remove(KEY_TARGET_URL)
                .remove(KEY_TARGET_SHA)
                .remove(KEY_TARGET_NOTES)
                .remove(KEY_TARGET_MANDATORY)
                .remove(KEY_TARGET_VERIFIED)
                .apply();
    }

    /** 删除旧 DownloadManager 任务及其元数据，随后即可为远程最新版本创建全新任务。 */
    private void discardPendingDownload() {
        long id = pendingDownloadId();
        if (id != NO_DOWNLOAD) downloadManager.remove(id);
        clearPendingDownload();
        dismissBlockingDialog();
        if (readyDialog != null) {
            readyDialog.dismiss();
            readyDialog = null;
        }
    }

    /**
     * 强制策略一旦从服务器成功取得便缓存到本地。这样用户退出、杀进程或暂时断网后，
     * 仍不能绕过已经生效的最低版本要求；安装到目标 versionCode 后自动清除。
     */
    private void saveCachedMandatory(UpdateInfo info) {
        preferences.edit()
                .putInt(KEY_CACHED_FORCE_CODE, info.versionCode)
                .putString(KEY_CACHED_FORCE_NAME, info.versionName)
                .putString(KEY_CACHED_FORCE_URL, info.apkUrl)
                .putString(KEY_CACHED_FORCE_SHA, info.sha256)
                .putString(KEY_CACHED_FORCE_NOTES, info.releaseNotes)
                .apply();
    }

    private UpdateInfo cachedMandatoryInfo() {
        int versionCode = preferences.getInt(KEY_CACHED_FORCE_CODE, 0);
        if (versionCode <= 0) return null;
        return new UpdateInfo(
                versionCode,
                preferences.getString(KEY_CACHED_FORCE_NAME, "新版本"),
                versionCode,
                true,
                preferences.getString(KEY_CACHED_FORCE_URL, ""),
                preferences.getString(KEY_CACHED_FORCE_SHA, ""),
                preferences.getString(KEY_CACHED_FORCE_NOTES, "")
        );
    }

    private void clearSatisfiedMandatoryCache() {
        int forcedCode = preferences.getInt(KEY_CACHED_FORCE_CODE, 0);
        if (forcedCode <= 0 || BuildConfig.VERSION_CODE < forcedCode) return;
        preferences.edit()
                .remove(KEY_CACHED_FORCE_CODE)
                .remove(KEY_CACHED_FORCE_NAME)
                .remove(KEY_CACHED_FORCE_URL)
                .remove(KEY_CACHED_FORCE_SHA)
                .remove(KEY_CACHED_FORCE_NOTES)
                .apply();
    }

    private long pendingDownloadId() {
        return preferences.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD);
    }

    private int pendingVersionCode() {
        return preferences.getInt(KEY_TARGET_CODE, 0);
    }

    private int skippedVersionCode() {
        return preferences.getInt(KEY_SKIPPED_VERSION, 0);
    }

    private boolean isPendingMandatory() {
        return preferences.getBoolean(KEY_TARGET_MANDATORY, false);
    }

    private String pendingVersionName() {
        return preferences.getString(KEY_TARGET_NAME, "新版本");
    }

    private void registerDownloadReceiver() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        // DOWNLOAD_COMPLETE 由系统 DownloadProvider 进程发出，需要 EXPORTED 才能接收；
        // onReceive 会再次核对系统返回的 downloadId 与本应用保存值，拒绝无关任务。
        ContextCompat.registerReceiver(
                activity, downloadReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        );
        receiverRegistered = true;
    }

    private void dismissBlockingDialog() {
        if (blockingDialog != null) {
            blockingDialog.dismiss();
            blockingDialog = null;
        }
    }

    /** 所有强制更新阶段统一从这里退出，确保重新启动时一定创建新的更新检查会话。 */
    private void exitApplication() {
        if (activity.getApplication() instanceof ElecApplication) {
            ((ElecApplication) activity.getApplication())
                    .endForegroundSessionForExplicitExit();
        }
        activity.finishAffinity();
    }

    private void runOnMain(Runnable action) {
        activity.runOnUiThread(() -> {
            if (activityUsable()) action.run();
        });
    }

    private boolean activityUsable() {
        return !activity.isFinishing()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1
                || !activity.isDestroyed());
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "未知错误" : message;
    }
}
