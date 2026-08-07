package com.shangzhili.electricityreminder;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** 集中展示并修复后台提醒所需的系统设置。 */
public final class SystemSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppThemeManager.wrap(newBase));
    }

    private static final int NOTIFICATION_PERMISSION_REQUEST = 101;
    private SetupPreferences setupPreferences;
    private int appliedThemeState;
    private TextView overallStatusText;
    private TextView notificationStatusText;
    private TextView exactAlarmStatusText;
    private TextView batteryStatusText;
    private TextView autoStartStatusText;
    private Button autoStartConfirmButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedThemeState = AppThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_system_settings);
        applySystemBarInsets();
        setupPreferences = new SetupPreferences(this);
        new NotificationHelper(this);

        overallStatusText = findViewById(R.id.overallStatusText);
        notificationStatusText = findViewById(R.id.notificationStatusText);
        exactAlarmStatusText = findViewById(R.id.exactAlarmStatusText);
        batteryStatusText = findViewById(R.id.batteryStatusText);
        autoStartStatusText = findViewById(R.id.autoStartStatusText);
        autoStartConfirmButton = findViewById(R.id.autoStartConfirmButton);
        setupThemeSelector();
        setupAppearanceSelector();
        setupProgressiveDisclosure();

        findViewById(R.id.settingsBackButton).setOnClickListener(view -> finish());
        findViewById(R.id.openNotificationSettingsButton)
                .setOnClickListener(view -> configureNotifications());
        findViewById(R.id.openExactAlarmSettingsButton)
                .setOnClickListener(view -> configureExactAlarm());
        findViewById(R.id.openBatterySettingsButton)
                .setOnClickListener(view -> configureBattery());
        findViewById(R.id.openAutoStartSettingsButton)
                .setOnClickListener(view -> openAutoStartSettings());
        findViewById(R.id.feedbackEmailButton)
                .setOnClickListener(view -> openFeedbackEmail());
        findViewById(R.id.filingNumberButton)
                .setOnClickListener(view -> openFilingQuery());
        findViewById(R.id.manualUpdateButton).setOnClickListener(view -> {
            toast("正在检查更新");
            ((ElecApplication) getApplication()).requestManualUpdate(this);
        });
        findViewById(R.id.dataStorageButton).setOnClickListener(view -> showInfo(
                "数据与存储",
                "房间配置、监测状态和查询记录主要保存在本机。云端公共历史仅用于补全趋势；服务器不可用时，当前余额查询、提醒和本地记录仍可正常使用。"
        ));
        findViewById(R.id.usageHelpButton).setOnClickListener(view -> showInfo(
                "使用说明",
                "首页可查看房间余额并快速充值；点击房间查看近 30 天余额趋势；在首页向右滑，或点击左上角电小侠，即可进入电小侠基地。低余额提醒需先在房间设置中开启监测。"
        ));
        autoStartConfirmButton.setOnClickListener(view -> {
            boolean confirmed = !setupPreferences.isAutoStartConfirmed();
            setupPreferences.setAutoStartConfirmed(confirmed);
            refreshStatus();
        });
    }

    /** 初始化时先勾选当前模式，再注册监听，避免页面打开就触发一次无意义的 recreate。 */
    private void setupThemeSelector() {
        MaterialButtonToggleGroup group = findViewById(R.id.themeModeGroup);
        int current = AppThemeManager.current(this);
        group.check(current == AppThemeManager.MODE_JADE
                ? R.id.themeJadeRadio
                : current == AppThemeManager.MODE_PURPLE
                ? R.id.themePurpleRadio : R.id.themeBlueRadio);
        group.addOnButtonCheckedListener((ignored, checkedId, isChecked) -> {
            if (!isChecked) return;
            int selected = checkedId == R.id.themeJadeRadio
                    ? AppThemeManager.MODE_JADE
                    : checkedId == R.id.themePurpleRadio
                    ? AppThemeManager.MODE_PURPLE : AppThemeManager.MODE_BLUE;
            if (selected == AppThemeManager.current(this)) return;
            AppThemeManager.save(this, selected);
            recreate();
        });
    }

    /**
     * 显示模式单独保存：更换深浅色不会重置蓝、绿、紫品牌色。
     * 选择后重建当前页面，使夜间限定资源从布局加载阶段就生效，避免短暂闪白。
     */
    private void setupAppearanceSelector() {
        MaterialButtonToggleGroup group = findViewById(R.id.appearanceModeGroup);
        int current = AppThemeManager.currentAppearance(this);
        group.check(current == AppThemeManager.APPEARANCE_DARK
                ? R.id.appearanceDarkRadio
                : current == AppThemeManager.APPEARANCE_FOLLOW_SYSTEM
                ? R.id.appearanceSystemRadio : R.id.appearanceLightRadio);
        group.addOnButtonCheckedListener((ignored, checkedId, isChecked) -> {
            if (!isChecked) return;
            int selected = checkedId == R.id.appearanceDarkRadio
                    ? AppThemeManager.APPEARANCE_DARK
                    : checkedId == R.id.appearanceSystemRadio
                    ? AppThemeManager.APPEARANCE_FOLLOW_SYSTEM
                    : AppThemeManager.APPEARANCE_LIGHT;
            if (selected == AppThemeManager.currentAppearance(this)) return;
            AppThemeManager.saveAppearance(this, selected);
            recreate();
        });
    }

    /**
     * 低频系统权限和品牌色默认收起，首屏只保留“是否就绪”和深浅模式。展开仅改变布局，
     * 不会清空输入或触发系统设置；因此用户可以在同一页按需深入，而不必面对长表单。
     */
    private void setupProgressiveDisclosure() {
        View systemContent = findViewById(R.id.advancedSystemContent);
        Button systemToggle = findViewById(R.id.systemDetailsToggle);
        systemToggle.setOnClickListener(view -> {
            boolean expand = systemContent.getVisibility() != View.VISIBLE;
            systemContent.setVisibility(expand ? View.VISIBLE : View.GONE);
            systemToggle.setText(expand ? "收起系统设置" : "查看系统设置");
        });
        View themeContent = findViewById(R.id.themeDetailsContent);
        Button themeToggle = findViewById(R.id.themeDetailsToggle);
        themeToggle.setOnClickListener(view -> {
            boolean expand = themeContent.getVisibility() != View.VISIBLE;
            themeContent.setVisibility(expand ? View.VISIBLE : View.GONE);
            themeToggle.setText(expand ? "收起主题颜色" : "更改主题颜色");
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedThemeState != AppThemeManager.state(this)) {
            recreate();
            return;
        }
        refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 通知权限弹窗关闭后立即刷新，不要求用户离开页面再回来。
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) refreshStatus();
    }

    private void refreshStatus() {
        SystemSettingsStatus status = SystemSettingsChecker.check(this);
        overallStatusText.setText(status.allReady()
                ? "✓ 后台提醒所需设置已全部就绪"
                : "还有 " + status.missingCount() + " 项设置需要处理");
        overallStatusText.setTextColor(getColor(status.allReady()
                ? R.color.status_normal : R.color.status_warning));
        applyStatus(notificationStatusText, status.notificationsReady, "通知权限与通知渠道");
        applyStatus(exactAlarmStatusText, status.exactAlarmReady, "精确闹钟权限");
        applyStatus(batteryStatusText, status.batteryReady, "忽略系统电池优化");
        applyStatus(
                autoStartStatusText, status.autoStartConfirmed,
                "厂商自启动（无法自动检测，需手动确认）"
        );
        autoStartConfirmButton.setText(status.autoStartConfirmed
                ? "撤销“已开启”确认" : "我已在系统中开启自启动");
    }

    private void applyStatus(TextView view, boolean ready, String label) {
        view.setText((ready ? "✓ " : "! ") + label + (ready ? "：已就绪" : "：需要设置"));
        view.setTextColor(getColor(ready ? R.color.status_normal : R.color.status_warning));
    }

    private void configureNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST
            );
            return;
        }
        Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.ALERT_CHANNEL);
        if (!tryStart(intent)) openAppDetails();
    }

    private void configureExactAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || Scheduler.canScheduleExactAlarms(this)) {
            toast("当前系统已允许精确闹钟");
            return;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:" + getPackageName()));
        if (!tryStart(intent)) openAppDetails();
    }

    private void configureBattery() {
        // 打开系统白名单列表让用户主动选择，避免申请可能不符合应用商店政策的直接豁免权限。
        if (!tryStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            openAppDetails();
        }
    }

    /**
     * Android 没有统一的“自启动”设置 Intent。这里按手机厂商尝试几个公开使用较多的
     * 管理页；系统升级导致页面不存在时，tryStart 会安全失败，最后退回本 App 详情页。
     * 该方法只负责导航，是否真的开启仍需用户在返回后点击“我已开启”确认。
     */
    private void openAutoStartSettings() {
        String manufacturer = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        List<ComponentName> candidates = new ArrayList<>();
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
            candidates.add(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ));
        } else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            candidates.add(new ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ));
            candidates.add(new ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.PurviewTabActivity"
            ));
        } else if (manufacturer.contains("oppo") || manufacturer.contains("oneplus")
                || manufacturer.contains("realme")) {
            candidates.add(new ComponentName(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.startupapp.StartupAppListActivity"
            ));
            candidates.add(new ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ));
        } else if (manufacturer.contains("huawei")) {
            candidates.add(new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ));
        } else if (manufacturer.contains("honor")) {
            candidates.add(new ComponentName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ));
        }

        for (ComponentName component : candidates) {
            if (tryStart(new Intent().setComponent(component))) return;
        }
        openAppDetails();
    }

    private void openAppDetails() {
        if (!tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + getPackageName())))) {
            toast("当前手机没有提供对应设置页面");
        }
    }

    /**
     * 反馈入口只使用标准 mailto 协议，不在应用内收集或上传用户输入。
     *
     * <p>部分手机没有安装邮件客户端，因此不能假设 ACTION_SENDTO 一定可用。无法打开时
     * 把地址复制到系统剪贴板，并用文字明确告知用户，既避免闪退，也方便改用其他邮箱。</p>
     */
    private void openFeedbackEmail() {
        String address = getString(R.string.feedback_email_address);
        String subject = getString(R.string.feedback_email_subject);
        Intent email = new Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("mailto:" + address + "?subject=" + Uri.encode(subject))
        );
        if (tryStart(email)) return;

        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("反馈邮箱", address));
            toast("未找到邮件应用，反馈邮箱已复制");
        } else {
            toast("反馈邮箱：" + address);
        }
    }

    /** 打开官方备案查询系统；若手机没有可处理 HTTPS 的应用，则给出明确反馈。 */
    private void openFilingQuery() {
        Intent query = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(getString(R.string.app_filing_query_url))
        );
        if (!tryStart(query)) toast("暂时无法打开备案查询页面");
    }

    private boolean tryStart(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException exception) {
            return false;
        }
    }

    private void applySystemBarInsets() {
        android.view.View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    view.getPaddingLeft(), insets.getSystemWindowInsetTop(),
                    view.getPaddingRight(), insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
        content.requestApplyInsets();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** 使用基地同款暖白弹窗，避免系统默认灰色面板破坏深浅主题下的品牌一致性。 */
    private void showInfo(String title, String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }
}
