package com.shangzhili.electricityreminder;

import android.app.Activity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * 单个房间的独立设置页。
 *
 * <p>备注保存和监测规则保存是两条互不影响的操作路径：保存备注只改 alias；保存并启用
 * 才会修改阈值并建立整点任务。这样用户不会因为改了一个名称而意外打开或关闭监测。</p>
 */
public final class RoomSettingsActivity extends Activity {
    private static final String EXTRA_ROOM_ID = "roomId";

    private int appliedThemeState;
    private String roomId;
    private RoomRepository repository;
    private EditText aliasInput;
    private EditText thresholdInput;
    private EditText recoveryInput;
    private EditText repeatMinutesInput;
    private RadioButton amountRadio;
    private TextView monitoringSummaryText;

    public static Intent createIntent(Context context, String roomId) {
        return new Intent(context, RoomSettingsActivity.class)
                .putExtra(EXTRA_ROOM_ID, roomId);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppThemeManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedThemeState = AppThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room_settings);
        applySystemBarInsets();

        roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        repository = new RoomRepository(this);
        if (roomId == null || !repository.isConfigured(roomId)) {
            Toast.makeText(this, "房间配置不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        aliasInput = findViewById(R.id.settingsAliasInput);
        thresholdInput = findViewById(R.id.settingsThresholdInput);
        recoveryInput = findViewById(R.id.settingsRecoveryInput);
        repeatMinutesInput = findViewById(R.id.settingsRepeatMinutesInput);
        amountRadio = findViewById(R.id.settingsAmountRadio);
        monitoringSummaryText = findViewById(R.id.settingsMonitoringSummaryText);

        findViewById(R.id.settingsBackButton).setOnClickListener(view -> finish());
        findViewById(R.id.settingsSaveAliasButton).setOnClickListener(view -> saveAliasOnly());
        findViewById(R.id.settingsEnableButton).setOnClickListener(view -> saveAndEnable());
        findViewById(R.id.settingsDisableButton).setOnClickListener(
                view -> confirmDisableMonitoring()
        );
        findViewById(R.id.settingsDeleteButton).setOnClickListener(view -> confirmDelete());
        populate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedThemeState != AppThemeManager.state(this)) {
            recreate();
            return;
        }
        refreshMonitoringSummary();
    }

    private void populate() {
        AppConfig config = repository.load(roomId);
        aliasInput.setText(config.alias);
        thresholdInput.setText(number(config.threshold));
        recoveryInput.setText(number(config.recoveryThreshold));
        repeatMinutesInput.setText(number(config.repeatMinutes));
        amountRadio.setChecked("amount".equals(config.metric));
        ((RadioButton) findViewById(R.id.settingsSurplusRadio))
                .setChecked("surplus".equals(config.metric));
        refreshMonitoringSummary();
    }

    /** 备注单独落盘，不读取也不保存当前表单中的提醒参数。 */
    private void saveAliasOnly() {
        try {
            String alias = repository.updateAlias(roomId, text(aliasInput));
            aliasInput.setText(alias);
            Toast.makeText(this, "房间备注已保存", Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException exception) {
            aliasInput.setError(exception.getMessage());
        }
    }

    private void saveAndEnable() {
        thresholdInput.setError(null);
        recoveryInput.setError(null);
        repeatMinutesInput.setError(null);
        try {
            AppConfig old = repository.load(roomId);
            double threshold = parse(thresholdInput, "请输入提醒阈值");
            double recovery = parse(recoveryInput, "请输入恢复阈值");
            double repeatMinutes = parse(repeatMinutesInput, "请输入复查间隔");
            if (recovery <= threshold) {
                recoveryInput.setError("恢复阈值必须大于提醒阈值");
                throw new IllegalArgumentException("请检查恢复阈值");
            }
            if (repeatMinutes < 1) {
                repeatMinutesInput.setError("复查间隔至少为 1 分钟");
                throw new IllegalArgumentException("请检查复查间隔");
            }

            AppConfig updated = new AppConfig(
                    old.alias,
                    old.roomCode,
                    amountRadio.isChecked() ? "amount" : "surplus",
                    threshold,
                    recovery,
                    repeatMinutes,
                    old.checkTimes
            );
            updated.validate();
            List<DailyCheckTime> previousLegacyTimes = old.checkTimes;
            repository.save(roomId, updated);
            repository.setMonitoringEnabled(roomId, true);
            // 先清理旧版自定义时间闹钟，再建立唯一的下一整点任务。
            Scheduler.cancelDailyAlarms(this, roomId, previousLegacyTimes);
            Scheduler.cancelRepeat(this, roomId);
            new MonitorState(this, roomId).resetLowAlertState();
            Scheduler.scheduleAll(this, roomId, updated.checkTimes);
            refreshMonitoringSummary();
            SystemSettingsStatus system = SystemSettingsChecker.check(this);
            Toast.makeText(
                    this,
                    system.allReady()
                            ? "提醒设置已保存，整点监测已启用"
                            : "已启用监测；系统设置仍有 " + system.missingCount() + " 项待处理",
                    Toast.LENGTH_LONG
            ).show();
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDisableMonitoring() {
        if (!repository.isMonitoringEnabled(roomId)) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("关闭余额监测")
                .setMessage("将停止整点查询和低余额重复提醒，但保留房间、历史和图表数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认关闭", (dialog, which) -> {
                    repository.setMonitoringEnabled(roomId, false);
                    Scheduler.cancelAllForRoom(this, roomId);
                    new MonitorState(this, roomId).resetLowAlertState();
                    refreshMonitoringSummary();
                    Toast.makeText(this, "余额监测已关闭", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除房间")
                .setMessage("将删除该房间配置、历史曲线和后台提醒，且无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    repository.delete(roomId);
                    // 清除仍显示已删除房间的详情页，直接回到现有首页实例。
                    Intent home = new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(home);
                    finish();
                })
                .show();
    }

    private void refreshMonitoringSummary() {
        if (monitoringSummaryText == null || roomId == null) return;
        boolean enabled = repository.isMonitoringEnabled(roomId);
        AppConfig config = repository.load(roomId);
        monitoringSummaryText.setText(String.format(
                Locale.CHINA,
                enabled
                        ? "监测中 · 每个整点查询 · 余额不足每 %.0f 分钟复查"
                        : "未开启 · 查询、历史同步、图表和手动刷新仍可正常使用",
                config.repeatMinutes
        ));
        monitoringSummaryText.setTextColor(getColor(
                enabled ? R.color.status_normal : R.color.text_secondary
        ));
        View disable = findViewById(R.id.settingsDisableButton);
        if (disable != null) disable.setEnabled(enabled);
    }

    private double parse(EditText input, String emptyMessage) {
        String value = text(input);
        if (value.isEmpty()) {
            input.setError(emptyMessage);
            throw new IllegalArgumentException(emptyMessage);
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            input.setError("请输入有效数字");
            throw new IllegalArgumentException("阈值和复查间隔必须填写数字");
        }
    }

    private String text(EditText input) {
        return input.getText().toString().trim();
    }

    private String number(double value) {
        return value == Math.rint(value)
                ? Long.toString((long) value) : Double.toString(value);
    }

    private void applySystemBarInsets() {
        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    view.getPaddingLeft(), insets.getSystemWindowInsetTop(),
                    view.getPaddingRight(), insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
        content.requestApplyInsets();
    }
}
