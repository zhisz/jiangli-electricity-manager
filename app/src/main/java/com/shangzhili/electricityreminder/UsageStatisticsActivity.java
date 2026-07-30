package com.shangzhili.electricityreminder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** 展示单个房间的上月、年度平均和近 12 个完整月份用电统计。 */
public final class UsageStatisticsActivity extends Activity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppThemeManager.wrap(newBase));
    }

    private static final String EXTRA_ROOM_ID = "roomId";
    private static final int HISTORY_DAYS = 400;
    private static final DateTimeFormatter MONTH_FORMAT =
            DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA);
    private int appliedThemeState;

    private ReadingHistoryStore historyStore;
    private List<HistoryPoint> points;
    private List<RechargeRecord> recharges;
    private ZoneId zoneId;
    private LocalDate currentMonthStart;
    private TextView currentMonthTitleText;
    private TextView currentMonthStatsText;
    private TextView lastMonthTitleText;
    private TextView lastMonthStatsText;
    private TextView yearTitleText;
    private TextView yearStatsText;
    private LinearLayout monthlyStatsContainer;

    public static Intent createIntent(Context context, String roomId) {
        return new Intent(context, UsageStatisticsActivity.class)
                .putExtra(EXTRA_ROOM_ID, roomId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedThemeState = AppThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usage_statistics);
        applySystemBarInsets();

        String roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        RoomRepository repository = new RoomRepository(this);
        if (roomId == null || !repository.contains(roomId)) {
            finish();
            return;
        }

        ((TextView) findViewById(R.id.usageStatsRoomText))
                .setText(repository.load(roomId).alias);
        currentMonthTitleText = findViewById(R.id.currentMonthTitleText);
        currentMonthStatsText = findViewById(R.id.currentMonthStatsText);
        lastMonthTitleText = findViewById(R.id.lastMonthTitleText);
        lastMonthStatsText = findViewById(R.id.lastMonthStatsText);
        yearTitleText = findViewById(R.id.yearTitleText);
        yearStatsText = findViewById(R.id.yearStatsText);
        monthlyStatsContainer = findViewById(R.id.monthlyStatsContainer);
        findViewById(R.id.usageStatsBackButton).setOnClickListener(view -> finish());

        zoneId = ZoneId.systemDefault();
        currentMonthStart = LocalDate.now(zoneId).withDayOfMonth(1);
        historyStore = new ReadingHistoryStore(this);
        // 原始读数仍按精确时间保存在数据库；这里按本机时区归并为每日最后一次读数。
        points = historyStore.loadDailyPoints(roomId, HISTORY_DAYS);
        recharges = historyStore.loadRecharges(roomId);
        renderCurrentMonth();
        renderLastMonth();
        renderCurrentYearAverage();
        renderRecentMonths();
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

    /**
     * 本月属于尚未结束的自然月，因此标题明确写“截至今日”，避免用户把当前累计值误认为整月结果。
     * 平均每日用电仍以真正有相邻采样覆盖的天数为分母，不把尚未到来的日期计为零用电。
     */
    private void renderCurrentMonth() {
        LocalDate endExclusive = LocalDate.now(zoneId).plusDays(1);
        UsagePeriodStats stats = calculate(currentMonthStart, endExclusive);
        currentMonthTitleText.setText(
                "本月用电 · " + MONTH_FORMAT.format(currentMonthStart) + "（截至今日）"
        );
        if (!stats.hasData()) {
            currentMonthStatsText.setText("本月有效数据不足。至少需要两个不同日期的余额采样。");
            return;
        }
        currentMonthStatsText.setText(String.format(
                Locale.CHINA,
                "本月累计 %.2f 度 · 约 %.2f 元\n"
                        + "平均每日 %.2f 度 · 约 %.2f 元\n"
                        + "有效采样覆盖 %d 天%s",
                stats.usageKwh,
                stats.costAmount,
                stats.usageKwh / stats.coveredDays,
                stats.costAmount / stats.coveredDays,
                stats.coveredDays,
                excludedText(stats)
        ));
    }

    /** 上月只使用完整自然月，并按有效覆盖天数计算平均每日用电。 */
    private void renderLastMonth() {
        LocalDate start = currentMonthStart.minusMonths(1);
        LocalDate end = currentMonthStart;
        UsagePeriodStats stats = calculate(start, end);
        lastMonthTitleText.setText("上月平均每日用电 · " + MONTH_FORMAT.format(start));
        if (!stats.hasData()) {
            lastMonthStatsText.setText("有效数据不足。至少需要两个不同日期的余额采样。");
            return;
        }
        lastMonthStatsText.setText(String.format(
                Locale.CHINA,
                "平均每日 %.2f 度 · 约 %.2f 元\n"
                        + "估算合计 %.2f 度 · 约 %.2f 元\n"
                        + "有效采样覆盖 %d 天%s",
                stats.usageKwh / stats.coveredDays,
                stats.costAmount / stats.coveredDays,
                stats.usageKwh,
                stats.costAmount,
                stats.coveredDays,
                excludedText(stats)
        ));
    }

    /**
     * “全年平均每月”只统计本年度已经结束的自然月，并以真正有有效采样的月份为分母。
     * 这样新安装 App 不会因为把安装前月份当作 0 而严重低估平均值。
     */
    private void renderCurrentYearAverage() {
        LocalDate yearStart = currentMonthStart.withDayOfYear(1);
        int monthsWithData = 0;
        for (LocalDate month = yearStart;
             month.isBefore(currentMonthStart);
             month = month.plusMonths(1)) {
            UsagePeriodStats stats = calculate(month, month.plusMonths(1));
            if (stats.hasData()) monthsWithData++;
        }
        // 全年合计一次性计算，避免一个跨月区间在逐月相加时重复统计“未匹配”段数。
        UsagePeriodStats yearStats = calculate(yearStart, currentMonthStart);

        int year = currentMonthStart.getYear();
        yearTitleText.setText(year + " 年平均每月用电（完整月份）");
        if (monthsWithData == 0) {
            yearStatsText.setText("本年度已经结束的月份中暂无足够数据。");
            return;
        }
        yearStatsText.setText(String.format(
                Locale.CHINA,
                "平均每月 %.2f 度 · 约 %.2f 元\n"
                        + "累计 %.2f 度 · 约 %.2f 元\n"
                        + "按 %d 个有记录月份计算 · 有效覆盖 %d 天%s",
                yearStats.usageKwh / monthsWithData,
                yearStats.costAmount / monthsWithData,
                yearStats.usageKwh,
                yearStats.costAmount,
                monthsWithData,
                yearStats.coveredDays,
                yearStats.excludedRechargeIntervals > 0
                        ? " · 未匹配上涨区间 "
                        + yearStats.excludedRechargeIntervals + " 段" : ""
        ));
    }

    /** 最近 12 个已经结束的自然月逐月列出，便于用户检查平均数来自哪些日期。 */
    private void renderRecentMonths() {
        monthlyStatsContainer.removeAllViews();
        for (int offset = 1; offset <= 12; offset++) {
            LocalDate start = currentMonthStart.minusMonths(offset);
            UsagePeriodStats stats = calculate(start, start.plusMonths(1));
            TextView row = new TextView(this);
            row.setTextColor(getColor(R.color.text_primary));
            row.setTextSize(15);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackgroundResource(R.drawable.room_card_background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.topMargin = dp(8);
            row.setLayoutParams(params);
            row.setText(stats.hasData()
                    ? String.format(
                            Locale.CHINA,
                            "%s\n估算 %.2f 度 · 约 %.2f 元 · 覆盖 %d 天%s",
                            MONTH_FORMAT.format(start), stats.usageKwh, stats.costAmount,
                            stats.coveredDays, excludedText(stats)
                    )
                    : MONTH_FORMAT.format(start) + "\n数据不足");
            monthlyStatsContainer.addView(row);
        }
    }

    private UsagePeriodStats calculate(LocalDate start, LocalDate end) {
        return UsageStatisticsCalculator.calculate(points, recharges, start, end, zoneId);
    }

    private String excludedText(UsagePeriodStats stats) {
        return stats.excludedRechargeIntervals > 0
                ? " · 未匹配上涨区间 " + stats.excludedRechargeIntervals + " 段" : "";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
