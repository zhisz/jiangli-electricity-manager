package com.shangzhili.electricityreminder;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.content.pm.PackageManager;

import java.util.Locale;

public final class NotificationHelper {
    /** MainActivity 用这个 ID 直接打开本渠道的系统设置页。 */
    public static final String ALERT_CHANNEL = "electricity_alerts";
    // Android 不允许提高已创建渠道的重要性，因此使用新 ID 把更新提升到高优先级。
    public static final String UPDATE_CHANNEL = "app_updates_priority_v2";
    public static final String ANNOUNCEMENT_CHANNEL = "developer_announcements";
    private final Context context;
    private final NotificationManager manager;

    public NotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        manager = context.getSystemService(NotificationManager.class);
        ensureChannels();
    }

    public void lowBalance(String roomId, AppConfig config, Reading reading) {
        String unit = config.metric.equals("amount") ? "元" : "度";
        String body = String.format(
                Locale.CHINA,
                "%s剩余 %.2f 度，折算 %.2f 元；提醒阈值 %.2f %s",
                config.alias, reading.surplus, reading.amount, config.threshold, unit
        );
        show(notificationId(roomId, 1), roomId, "该缴电费了", body);
    }

    /** 重复查询仍未达到恢复阈值时更新同一条通知，避免通知栏堆积大量消息。 */
    public void stillLowBalance(String roomId, AppConfig config, Reading reading) {
        String unit = config.metric.equals("amount") ? "元" : "度";
        String body = String.format(
                Locale.CHINA,
                "%s当前剩余 %.2f 度，折算 %.2f 元；仍低于恢复阈值 %.2f %s",
                config.alias, reading.surplus, reading.amount, config.recoveryThreshold, unit
        );
        show(notificationId(roomId, 1), roomId, "电费余额仍未恢复", body);
    }

    public void authExpired(String roomId, String alias) {
        show(
                notificationId(roomId, 2), roomId,
                "电量监测凭据失效", alias + "的内置 JID 已失效，请更新源码后重新构建 App。"
        );
    }

    public void monitorFailure(String roomId, String alias, String message) {
        show(
                notificationId(roomId, 3), roomId,
                "电量监测连续失败", alias + "：" + message
        );
    }

    /** 官方订单确认在后台完成时使用通知反馈，避免 Activity 不可见时尝试弹出窗口。 */
    public void rechargeConfirmed(String roomId, String alias, String amount) {
        // 同一笔尝试先“暂未确认”、稍后重试成功时，移除旧提示，避免通知栏同时出现
        // “未到账”和“已到账”两种相互矛盾的状态。
        manager.cancel(notificationId(roomId, 5));
        manager.cancel(notificationId(roomId, 6));
        show(
                notificationId(roomId, 4), roomId,
                "电费充值成功",
                alias + "的校付宝订单已确认支付成功，"
                        + amount + " 元已自动加入充值记录。"
        );
    }

    /** 用户已在房间页看到持久化结果后，通知栏不再保留同一房间的旧充值状态。 */
    public void clearRechargeResultNotifications(String roomId) {
        manager.cancel(notificationId(roomId, 4));
        manager.cancel(notificationId(roomId, 5));
        manager.cancel(notificationId(roomId, 6));
    }

    public void rechargeUnconfirmed(String roomId, String alias) {
        show(
                notificationId(roomId, 5), roomId,
                "暂时无法确认充值到账",
                alias + "的校付宝订单尚未返回成功终态；可能未支付、尚未同步或网络不可用。"
        );
    }

    /** 只有校付宝订单详情明确返回失败终态时才调用，暂时查不到记录不会走这里。 */
    public void rechargeFailed(String roomId, String alias) {
        manager.cancel(notificationId(roomId, 4));
        manager.cancel(notificationId(roomId, 5));
        show(
                notificationId(roomId, 6), roomId,
                "电费充值未成功",
                alias + "的校付宝官方订单显示支付未成功，本次未写入充值记录。"
        );
    }

    /**
     * 新版本用独立低打扰渠道发系统通知。每个 versionCode 只通知一次；更新弹窗仍由
     * MainActivity 按强制/可选策略处理，点击通知只负责把应用带回前台。
     */
    public void appUpdate(UpdateInfo info) {
        if (info == null || info.versionCode <= BuildConfig.VERSION_CODE) return;
        android.content.SharedPreferences preferences = context.getSharedPreferences(
                "app_update_notification", Context.MODE_PRIVATE
        );
        if (preferences.getInt("notifiedVersionCode", 0) >= info.versionCode) return;
        String body = info.isMandatoryFor(BuildConfig.VERSION_CODE)
                ? "版本 " + info.versionName + " 必须更新，点击打开应用并下载安装。"
                : "版本 " + info.versionName + " 已发布，点击打开应用查看更新内容。";
        if (show(9_001, null, "江理电小侠有新版本", body, UPDATE_CHANNEL)) {
            preferences.edit().putInt("notifiedVersionCode", info.versionCode).apply();
        }
    }

    /** 公告使用独立默认重要性渠道，既能被系统展示，也允许用户单独调整打扰级别。 */
    public boolean announcement(Announcement announcement) {
        if (announcement == null || announcement.id <= 0) return false;
        int id = announcementNotificationId(announcement.id);
        return show(id, null, announcement.title, announcement.content, ANNOUNCEMENT_CHANNEL);
    }

    public void cancelAnnouncement(long announcementId) {
        manager.cancel(announcementNotificationId(announcementId));
    }

    private void show(int id, String roomId, String title, String body) {
        show(id, roomId, title, body, ALERT_CHANNEL);
    }

    private boolean show(
            int id, String roomId, String title, String body, String channelId
    ) {
        // Android 13 起，未授予 POST_NOTIFICATIONS 时直接结束。这样后台查询仍会被记录，
        // 但不会因通知权限被拒绝而把一次成功查询错误地记成监测失败。
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        Intent intent = roomId == null
                ? new Intent(context, MainActivity.class)
                : RoomDetailActivity.createIntent(context, roomId);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        android.app.Notification notification = new android.app.Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
        manager.notify(id, notification);
        return true;
    }

    /** 不同房间使用不同通知 ID，某个房间的重复通知只更新自己的那一条。 */
    private int notificationId(String roomId, int type) {
        return 10_000 + Math.abs(roomId.hashCode() % 8_000) * 10 + type;
    }

    private void ensureChannels() {
        // minSdk 已是 26，因此所有支持的设备都有通知渠道 API，无需再做版本分支。
        NotificationChannel channel = new NotificationChannel(
                ALERT_CHANNEL, "电费提醒", NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("低余额、充值结果、登录失效和监测故障提醒");
        manager.createNotificationChannel(channel);
        NotificationChannel updates = new NotificationChannel(
                UPDATE_CHANNEL, "应用更新", NotificationManager.IMPORTANCE_HIGH
        );
        updates.setDescription("新版本发布与必须更新提醒");
        manager.createNotificationChannel(updates);
        NotificationChannel announcements = new NotificationChannel(
                ANNOUNCEMENT_CHANNEL, "开发者公告", NotificationManager.IMPORTANCE_DEFAULT
        );
        announcements.setDescription("江理电小侠的服务通知、功能说明与校园用电公告");
        manager.createNotificationChannel(announcements);
    }

    private int announcementNotificationId(long announcementId) {
        return 20_000 + (int) Math.abs(announcementId % 10_000);
    }
}
