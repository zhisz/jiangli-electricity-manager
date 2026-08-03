package com.shangzhili.electricityreminder;

import android.app.Activity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/** 让用户按房间、日期登记充值金额，并管理误填记录。 */
public final class RechargeRecordsActivity extends Activity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppThemeManager.wrap(newBase));
    }

    private static final String EXTRA_ROOM_ID = "roomId";
    private static final int RETENTION_DAYS = 400;
    private int appliedThemeState;
    private String roomId;
    private ReadingHistoryStore historyStore;
    private EditText rechargeDateInput;
    private EditText rechargeTimeInput;
    private EditText rechargeAmountInput;
    private LinearLayout rechargeRecordsContainer;

    public static Intent createIntent(Context context, String roomId) {
        return new Intent(context, RechargeRecordsActivity.class)
                .putExtra(EXTRA_ROOM_ID, roomId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedThemeState = AppThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recharge_records);
        applySystemBarInsets();

        roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        RoomRepository repository = new RoomRepository(this);
        if (roomId == null || !repository.contains(roomId)) {
            finish();
            return;
        }

        historyStore = new ReadingHistoryStore(this);
        rechargeDateInput = findViewById(R.id.rechargeDateInput);
        rechargeTimeInput = findViewById(R.id.rechargeTimeInput);
        rechargeAmountInput = findViewById(R.id.rechargeAmountInput);
        rechargeRecordsContainer = findViewById(R.id.rechargeRecordsContainer);
        ((TextView) findViewById(R.id.rechargeRoomText)).setText(repository.load(roomId).alias);
        rechargeDateInput.setText(LocalDate.now().toString());
        rechargeDateInput.setOnClickListener(view -> showDatePicker());
        rechargeTimeInput.setText(LocalTime.now().withSecond(0).withNano(0).toString());
        rechargeTimeInput.setOnClickListener(view -> showTimePicker());
        findViewById(R.id.rechargeBackButton).setOnClickListener(view -> finish());
        findViewById(R.id.saveRechargeButton).setOnClickListener(view -> saveRecharge());
        renderRecords();
    }

    @Override
    protected void onDestroy() {
        if (historyStore != null) historyStore.close();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedThemeState != AppThemeManager.state(this)) recreate();
    }

    private void saveRecharge() {
        try {
            LocalDate date = LocalDate.parse(rechargeDateInput.getText().toString().trim());
            LocalTime time = LocalTime.parse(
                    rechargeTimeInput.getText().toString().trim(),
                    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
            );
            LocalDate today = LocalDate.now();
            if (date.isAfter(today)) throw new IllegalArgumentException("充值日期不能晚于今天");
            if (date.isBefore(today.minusDays(RETENTION_DAYS))) {
                throw new IllegalArgumentException("只能登记最近 400 天内的充值");
            }
            LocalDateTime selected = LocalDateTime.of(date, time);
            if (selected.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    > System.currentTimeMillis()) {
                throw new IllegalArgumentException("充值时间不能晚于当前时间");
            }
            double amount = Double.parseDouble(rechargeAmountInput.getText().toString().trim());
            if (!Double.isFinite(amount) || amount <= 0 || amount > 100_000) {
                throw new IllegalArgumentException("充值金额必须大于 0 且不超过 100000 元");
            }
            historyStore.recordRecharge(roomId, date, time, amount);
            rechargeAmountInput.setText("");
            renderRecords();
            toast("充值记录已保存；同一天可继续登记其他充值");
        } catch (DateTimeParseException exception) {
            toast("日期请按 YYYY-MM-DD 填写，例如 2026-07-15");
        } catch (NumberFormatException exception) {
            toast("请输入正确的充值金额");
        } catch (IllegalArgumentException exception) {
            toast(exception.getMessage());
        }
    }

    /** 使用系统日期选择器生成固定 ISO 日期，避免不同输入法产生斜杠或中文日期格式。 */
    private void showDatePicker() {
        LocalDate selected;
        try {
            selected = LocalDate.parse(rechargeDateInput.getText().toString().trim());
        } catch (DateTimeParseException ignored) {
            selected = LocalDate.now();
        }
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (view, year, month, day) -> rechargeDateInput.setText(
                        LocalDate.of(year, month + 1, day).toString()
                ),
                selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth()
        );
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.getDatePicker().setMinDate(
                System.currentTimeMillis() - RETENTION_DAYS * 24L * 60 * 60 * 1_000
        );
        dialog.show();
    }

    /** 充值时间精确到分钟，使一天内多次充值可以分别落入正确的小时采样区间。 */
    private void showTimePicker() {
        LocalTime selected;
        try {
            selected = LocalTime.parse(
                    rechargeTimeInput.getText().toString().trim(),
                    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
            );
        } catch (DateTimeParseException ignored) {
            selected = LocalTime.now();
        }
        new TimePickerDialog(
                this,
                (view, hour, minute) -> rechargeTimeInput.setText(String.format(
                        Locale.CHINA, "%02d:%02d", hour, minute
                )),
                selected.getHour(), selected.getMinute(), true
        ).show();
    }

    /** 记录按日期倒序展示；误填时必须先确认再删除，避免统计结果无提示地变化。 */
    private void renderRecords() {
        rechargeRecordsContainer.removeAllViews();
        List<RechargeRecord> records = historyStore.loadRecharges(roomId);
        if (records.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有充值记录");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setPadding(0, dp(14), 0, dp(14));
            rechargeRecordsContainer.addView(empty);
            return;
        }

        for (int index = records.size() - 1; index >= 0; index--) {
            RechargeRecord record = records.get(index);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(8), dp(8), dp(8));
            row.setBackgroundResource(R.drawable.room_card_background);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowParams.topMargin = dp(8);
            row.setLayoutParams(rowParams);

            TextView value = new TextView(this);
            value.setText(String.format(
                    Locale.CHINA, "%s %02d:%02d\n充值 %.2f 元",
                    record.date, record.time.getHour(), record.time.getMinute(), record.amount
            ));
            value.setTextColor(getColor(R.color.text_primary));
            value.setTextSize(16);
            row.addView(value, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1
            ));

            Button delete = new Button(this, null, 0, R.style.Ui_Button_DangerText);
            delete.setText("删除");
            delete.setOnClickListener(view -> confirmDelete(record));
            row.addView(delete, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            rechargeRecordsContainer.addView(row);
        }
    }

    private void confirmDelete(RechargeRecord record) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除充值记录")
                .setMessage(String.format(
                        Locale.CHINA, "确认删除 %s %02d:%02d 的 %.2f 元充值记录？",
                        record.date, record.time.getHour(), record.time.getMinute(), record.amount
                ))
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    historyStore.deleteRecharge(roomId, record.id);
                    renderRecords();
                })
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
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
