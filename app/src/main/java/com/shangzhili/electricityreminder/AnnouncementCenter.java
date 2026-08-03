package com.shangzhili.electricityreminder;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 公告的本地协调层。
 *
 * <p>服务器只作为增量来源：每条公告先写入 SharedPreferences 待读队列，再更新游标。
 * 因此进程在两步之间退出也不会丢公告；网络失败时继续展示已经下载的内容，不阻塞主页。
 * “送达”和“已读”严格分开，只有用户点击“我知道了”才发已读回执。</p>
 */
public final class AnnouncementCenter {
    private static final String TAG = "AnnouncementCenter";
    private static final String PREFS = "developer_announcements";
    private static final String KEY_LAST_RECEIVED_ID = "lastReceivedId";
    private static final String KEY_UNREAD = "unreadJson";
    private static final String KEY_NOTIFIED = "notifiedIds";
    private static final String KEY_PENDING_READS = "pendingReadIds";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean SYNCING = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AnnouncementCenter() {}

    /** 前台先刷新增量，再在主线程逐条展示本地待读公告。 */
    public static void checkAndShow(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        sync(activity.getApplicationContext(), false, () -> showNext(activity));
    }

    /** WorkManager 使用该入口：只发系统通知，不尝试在后台弹窗口。 */
    public static void checkInBackground(Context context) {
        checkInBackground(context, null);
    }

    static void checkInBackground(Context context, Runnable completion) {
        sync(context.getApplicationContext(), true, completion);
    }

    private static void sync(Context context, boolean notify, Runnable completion) {
        String baseUrl = BuildConfig.ELEC_SERVICE_BASE_URL.trim();
        if (baseUrl.isEmpty()) {
            if (completion != null) MAIN.post(completion);
            return;
        }
        if (!SYNCING.compareAndSet(false, true)) {
            if (completion != null) MAIN.postDelayed(completion, 350);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                retryPendingReads(context, baseUrl);
                SharedPreferences preferences = preferences(context);
                long afterId = preferences.getLong(KEY_LAST_RECEIVED_ID, 0);
                AnnouncementClient.SyncResult result = new AnnouncementClient().sync(
                        baseUrl, UsageReporter.deviceIdentity(context), afterId
                );
                Map<Long, Announcement> unread = readUnread(preferences);
                long newest = afterId;
                for (Announcement row : result.announcements) {
                    unread.put(row.id, row);
                    newest = Math.max(newest, row.id);
                }
                Set<String> notified = preferences.getStringSet(
                        KEY_NOTIFIED, Collections.emptySet()
                );
                Set<String> notifiedCopy = new java.util.HashSet<>(notified);
                NotificationHelper notificationHelper = new NotificationHelper(context);
                for (Long withdrawnId : result.withdrawnIds) {
                    unread.remove(withdrawnId);
                    notifiedCopy.remove(Long.toString(withdrawnId));
                    notificationHelper.cancelAnnouncement(withdrawnId);
                }
                if (notify) {
                    for (Announcement row : unread.values()) {
                        String id = Long.toString(row.id);
                        if (!notifiedCopy.contains(id)
                                && notificationHelper.announcement(row)) {
                            // 没有通知权限时不提前标记；用户以后授权后，后台任务仍可补发。
                            notifiedCopy.add(id);
                        }
                    }
                }
                if (!result.announcements.isEmpty() || !result.withdrawnIds.isEmpty()
                        || !notifiedCopy.equals(notified)) {
                    // 待读队列与游标放在同一次事务式 Editor 提交，避免只推进游标却丢内容。
                    preferences.edit()
                            .putString(KEY_UNREAD, encodeUnread(unread))
                            .putLong(KEY_LAST_RECEIVED_ID, newest)
                            .putStringSet(KEY_NOTIFIED, notifiedCopy)
                            .apply();
                }
            } catch (Exception exception) {
                // 公告是附加能力；服务端离线、超时和坏响应全部静默，不影响任何核心功能。
                if (BuildConfig.DEBUG) Log.w(TAG, "公告同步静默失败", exception);
            } finally {
                SYNCING.set(false);
                if (completion != null) MAIN.post(completion);
            }
        });
    }

    private static void showNext(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        SharedPreferences preferences = preferences(activity);
        List<Announcement> unread = new ArrayList<>(readUnread(preferences).values());
        if (unread.isEmpty()) return;
        Announcement item = unread.get(0);
        String time = formatTime(item.publishedAt);
        String message = item.content + (time.isEmpty() ? "" : "\n\n" + time);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(item.title)
                .setMessage(message)
                .setPositiveButton("我知道了", (dialog, which) -> {
                    acknowledge(activity, item.id);
                    MAIN.postDelayed(() -> showNext(activity), 180);
                })
                .setCancelable(false)
                .show();
    }

    private static void acknowledge(Context context, long id) {
        SharedPreferences preferences = preferences(context);
        Map<Long, Announcement> unread = readUnread(preferences);
        unread.remove(id);
        Set<String> pending = new java.util.HashSet<>(
                preferences.getStringSet(KEY_PENDING_READS, Collections.emptySet())
        );
        pending.add(Long.toString(id));
        preferences.edit()
                .putString(KEY_UNREAD, encodeUnread(unread))
                .putStringSet(KEY_PENDING_READS, pending)
                .apply();
        String baseUrl = BuildConfig.ELEC_SERVICE_BASE_URL.trim();
        if (!baseUrl.isEmpty()) EXECUTOR.execute(() -> retryPendingReads(context, baseUrl));
    }

    private static void retryPendingReads(Context context, String baseUrl) {
        SharedPreferences preferences = preferences(context);
        Set<String> pending = new java.util.HashSet<>(
                preferences.getStringSet(KEY_PENDING_READS, Collections.emptySet())
        );
        if (pending.isEmpty()) return;
        AnnouncementClient client = new AnnouncementClient();
        for (String value : new ArrayList<>(pending)) {
            try {
                client.markRead(baseUrl, UsageReporter.deviceIdentity(context), Long.parseLong(value));
                pending.remove(value);
                preferences.edit().putStringSet(KEY_PENDING_READS, pending).apply();
            } catch (IOException | NumberFormatException exception) {
                // 有限重试：本轮遇到网络错误立即停止，下一次前台或后台同步再补发。
                break;
            }
        }
    }

    private static Map<Long, Announcement> readUnread(SharedPreferences preferences) {
        Map<Long, Announcement> result = new LinkedHashMap<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_UNREAD, "[]"));
            for (int index = 0; index < array.length(); index++) {
                JSONObject row = array.optJSONObject(index);
                if (row == null) continue;
                long id = row.optLong("id", 0);
                if (id > 0) result.put(id, new Announcement(
                        id, row.optString("title"), row.optString("content"),
                        row.optString("publishedAt")
                ));
            }
        } catch (Exception ignored) {
            // 本地公告缓存损坏时只丢弃附加数据；下一次服务器同步仍可继续工作。
        }
        return result;
    }

    private static String encodeUnread(Map<Long, Announcement> values) {
        JSONArray array = new JSONArray();
        for (Announcement item : values.values()) {
            try {
                array.put(new JSONObject()
                        .put("id", item.id)
                        .put("title", item.title)
                        .put("content", item.content)
                        .put("publishedAt", item.publishedAt));
            } catch (Exception ignored) {}
        }
        return array.toString();
    }

    private static String formatTime(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(SHANGHAI).format(DISPLAY_TIME);
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
