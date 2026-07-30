package com.shangzhili.electricityreminder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 最近 30 天“余额 + 时段耗电速率”组合图。
 *
 * <p>上半区使用真实时间间距绘制余额折线；数据源连续不变后才批量跳变时，折线会按确认
 * 窗口均摊为虚线估算，不再制造“数小时为零、最后一小时暴增”。细点线表示仍等待数据源
 * 下一次结算。每一段线和下方速率柱共用速度颜色，橙色虚线则逐笔标出充值。</p>
 */
public final class ElectricityTrendView extends View {
    public interface OnTrendPointSelectedListener {
        void onSelected(BalanceTrendPoint point);
    }

    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rechargePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unmatchedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path areaPath = new Path();
    private final DashPathEffect estimatedDash =
            new DashPathEffect(new float[]{dp(7), dp(4)}, 0);
    private final DashPathEffect awaitingDash =
            new DashPathEffect(new float[]{dp(2), dp(4)}, 0);
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("MM/dd HH:mm", Locale.CHINA);
    private final SimpleDateFormat dayFormat =
            new SimpleDateFormat("MM/dd", Locale.CHINA);
    private final Date labelDate = new Date();
    private final List<BalanceTrendPoint> points = new ArrayList<>();
    private final List<RechargeRecord> recharges = new ArrayList<>();
    private boolean showAmount;
    private int selectedIndex = -1;
    private OnTrendPointSelectedListener listener;

    public ElectricityTrendView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        axisPaint.setColor(context.getColor(R.color.border));
        axisPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(context.getColor(R.color.chart_grid));
        gridPaint.setStrokeWidth(dp(1));
        segmentPaint.setStyle(Paint.Style.STROKE);
        segmentPaint.setStrokeWidth(dp(3));
        segmentPaint.setStrokeCap(Paint.Cap.ROUND);
        pointPaint.setColor(AppThemeManager.color(context, R.attr.uiPrimary));
        rechargePaint.setColor(context.getColor(R.color.chart_recharge));
        rechargePaint.setStrokeWidth(dp(1.5f));
        rechargePaint.setPathEffect(new DashPathEffect(
                new float[]{dp(4), dp(4)}, 0
        ));
        unmatchedPaint.setColor(context.getColor(R.color.chart_recharge));
        unmatchedPaint.setStyle(Paint.Style.STROKE);
        unmatchedPaint.setStrokeWidth(dp(2));
        selectionPaint.setColor(withAlpha(
                AppThemeManager.color(context, R.attr.uiPrimary), 0x55
        ));
        selectionPaint.setStrokeWidth(dp(1.5f));
        textPaint.setColor(context.getColor(R.color.text_secondary));
        textPaint.setTextSize(dp(10.5f));
    }

    public void setData(
            List<BalanceTrendPoint> trendPoints,
            List<RechargeRecord> rechargeRecords
    ) {
        points.clear();
        recharges.clear();
        if (trendPoints != null) points.addAll(trendPoints);
        if (rechargeRecords != null) recharges.addAll(rechargeRecords);
        selectedIndex = -1;
        updateContentDescription();
        // View 位于 HorizontalScrollView 内，宽度随真实时间跨度变化；数据刷新后必须重新测量。
        requestLayout();
        invalidate();
    }

    public void setShowAmount(boolean showAmount) {
        if (this.showAmount == showAmount) return;
        this.showAmount = showAmount;
        updateContentDescription();
        invalidate();
    }

    public void setOnTrendPointSelectedListener(OnTrendPointSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        int primary = AppThemeManager.color(getContext(), R.attr.uiPrimary);
        areaPaint.setShader(new LinearGradient(
                0f, dp(14), 0f, Math.max(dp(15), height - dp(76)),
                withAlpha(primary, 0x2E), withAlpha(primary, 0x03), Shader.TileMode.CLAMP
        ));
        areaPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        long spanMillis = points.size() < 2 ? 0
                : points.get(points.size() - 1).reading.timestamp
                - points.get(0).reading.timestamp;
        double spanHours = Math.max(1.0, spanMillis / 3_600_000.0);
        // 每个真实小时分配 6dp。30 天约 4320dp，既能看清速率变化，又可流畅向左回看。
        int desiredWidth = Math.round(Math.max(dp(320), (float) (spanHours * dp(6))));
        int desiredHeight = Math.round(dp(260));
        int measuredWidth = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(widthMeasureSpec) : desiredWidth;
        int measuredHeight = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec)
                : resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("暂无小时级历史数据", getWidth() / 2f, getHeight() / 2f, textPaint);
            return;
        }

        float left = dp(8);
        float right = getWidth() - dp(10);
        float top = dp(16);
        float balanceBottom = getHeight() - dp(78);
        float rateTop = balanceBottom + dp(17);
        float rateBottom = getHeight() - dp(29);
        long startTime = points.get(0).reading.timestamp;
        long endTime = points.get(points.size() - 1).reading.timestamp;
        long timeRange = Math.max(1L, endTime - startTime);

        double[] scale = calculateValueScale(points, showAmount);
        double chartMin = scale[0];
        double chartMax = scale[1];
        double valueRange = Math.max(0.5, chartMax - chartMin);
        double maxRate = 0;
        for (BalanceTrendPoint point : points) {
            if (point.rateValid) {
                maxRate = Math.max(maxRate, displayedRate(point));
            }
        }
        maxRate = Math.max(0.01, maxRate);

        // 三条横向网格随内容滑动；数值刻度由外层固定的 BalanceTrendAxisView 绘制。
        for (int level = 0; level <= 2; level++) {
            float y = top + (balanceBottom - top) * level / 2f;
            canvas.drawLine(left, y, right, y, gridPaint);
        }
        canvas.drawLine(left, balanceBottom, right, balanceBottom, axisPaint);
        canvas.drawLine(left, rateBottom, right, rateBottom, axisPaint);

        // 面积使用直线连接，避免平滑曲线在充值或快速消耗处制造并不存在的中间数值。
        areaPath.reset();
        for (int index = 0; index < points.size(); index++) {
            BalanceTrendPoint point = points.get(index);
            float x = xOf(point.reading.timestamp, startTime, timeRange, left, right);
            float y = yOf(displayedValue(point), chartMin, valueRange, top, balanceBottom);
            if (index == 0) areaPath.moveTo(x, y);
            else areaPath.lineTo(x, y);
        }
        areaPath.lineTo(right, balanceBottom);
        areaPath.lineTo(left, balanceBottom);
        areaPath.close();
        canvas.drawPath(areaPath, areaPaint);

        for (int index = 1; index < points.size(); index++) {
            BalanceTrendPoint previous = points.get(index - 1);
            BalanceTrendPoint current = points.get(index);
            float previousX = xOf(previous.reading.timestamp, startTime, timeRange, left, right);
            float currentX = xOf(current.reading.timestamp, startTime, timeRange, left, right);
            float previousY = yOf(
                    displayedValue(previous), chartMin, valueRange, top, balanceBottom
            );
            float currentY = yOf(
                    displayedValue(current), chartMin, valueRange, top, balanceBottom
            );
            int color = rateColor(current, maxRate);
            segmentPaint.setColor(current.estimated ? withAlpha(color, 0xCC) : color);
            segmentPaint.setPathEffect(current.awaitingSourceUpdate
                    ? awaitingDash : (current.estimated ? estimatedDash : null));
            canvas.drawLine(previousX, previousY, currentX, currentY, segmentPaint);
            segmentPaint.setPathEffect(null);
            if (current.unmatchedIncrease) {
                // 没有充值记录却出现上涨时画空心橙点，提醒用户补录，而不是把它当作零耗电。
                canvas.drawCircle(currentX, currentY, dp(4.5f), unmatchedPaint);
            }

            // 速率柱与上方对应线段同色，宽度等于真实时间区间；无效上涨区间画灰色短线。
            if (current.rateValid) {
                float rateHeight = (float) (displayedRate(current) / maxRate)
                        * (rateBottom - rateTop);
                float barLeft = Math.min(previousX, currentX) + dp(1);
                float barRight = Math.max(previousX, currentX) - dp(1);
                if (barRight <= barLeft) barRight = barLeft + dp(1);
                segmentPaint.setStyle(Paint.Style.FILL);
                segmentPaint.setColor(current.estimated ? withAlpha(color, 0xB5) : color);
                canvas.drawRoundRect(
                        barLeft, rateBottom - Math.max(dp(2), rateHeight),
                        barRight, rateBottom, dp(2), dp(2), segmentPaint
                );
                segmentPaint.setStyle(Paint.Style.STROKE);
            } else {
                canvas.drawLine(previousX, rateBottom - dp(2), currentX,
                        rateBottom - dp(2), gridPaint);
            }
        }

        // 每笔充值按自己的分钟位置绘制。即使两次充值发生在同一天，也会出现两条标记。
        for (RechargeRecord recharge : recharges) {
            if (recharge.timestamp < startTime || recharge.timestamp > endTime) continue;
            float x = xOf(recharge.timestamp, startTime, timeRange, left, right);
            canvas.drawLine(x, top, x, rateBottom, rechargePaint);
            canvas.drawCircle(x, top + dp(4), dp(3), rechargePaint);
        }

        // 最新余额点与用户选中的区间拥有独立反馈，但不把每个小时都画成密集圆点。
        BalanceTrendPoint latest = points.get(points.size() - 1);
        float latestX = xOf(latest.reading.timestamp, startTime, timeRange, left, right);
        float latestY = yOf(displayedValue(latest), chartMin, valueRange, top, balanceBottom);
        canvas.drawCircle(latestX, latestY, dp(4), pointPaint);
        if (selectedIndex >= 1 && selectedIndex < points.size()) {
            BalanceTrendPoint selected = points.get(selectedIndex);
            float selectedX = xOf(selected.reading.timestamp, startTime, timeRange, left, right);
            canvas.drawLine(selectedX, top, selectedX, rateBottom, selectionPaint);
            canvas.drawCircle(
                    selectedX,
                    yOf(displayedValue(selected), chartMin, valueRange, top, balanceBottom),
                    dp(6), selectionPaint
            );
        }

        drawTimeLabels(canvas, startTime, endTime, left, right);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(showAmount ? "时段费率" : "小时速率", left, rateTop - dp(4), textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP && points.size() >= 2) {
            performClick();
            float left = dp(8);
            float right = getWidth() - dp(10);
            float fraction = Math.max(0f, Math.min(1f, (event.getX() - left) / (right - left)));
            long start = points.get(0).reading.timestamp;
            long end = points.get(points.size() - 1).reading.timestamp;
            long touchedTime = start + (long) ((end - start) * fraction);
            int nearest = 1;
            long smallestDistance = Long.MAX_VALUE;
            for (int index = 1; index < points.size(); index++) {
                long distance = Math.abs(points.get(index).reading.timestamp - touchedTime);
                if (distance < smallestDistance) {
                    smallestDistance = distance;
                    nearest = index;
                }
            }
            selectedIndex = nearest;
            invalidate();
            if (listener != null) listener.onSelected(points.get(nearest));
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void drawTimeLabels(
            Canvas canvas, long startTime, long endTime, float left, float right
    ) {
        labelDate.setTime(startTime);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(timeFormat.format(labelDate), left, getHeight() - dp(8), textPaint);
        // 长时间轴按天标注；固定 6dp/小时意味着相邻日期约相隔 144dp，不会互相覆盖。
        long dayMillis = 24L * 60 * 60 * 1_000;
        for (long timestamp = startTime + dayMillis;
             timestamp < endTime - dayMillis / 2;
             timestamp += dayMillis) {
            labelDate.setTime(timestamp);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(
                    dayFormat.format(labelDate),
                    xOf(timestamp, startTime, Math.max(1L, endTime - startTime), left, right),
                    getHeight() - dp(8), textPaint
            );
        }
        labelDate.setTime(endTime);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(timeFormat.format(labelDate), right, getHeight() - dp(8), textPaint);
    }

    private int rateColor(BalanceTrendPoint point, double maxRate) {
        if (!point.rateValid) return getContext().getColor(R.color.text_tertiary);
        double ratio = Math.min(1, displayedRate(point) / maxRate);
        int primary = AppThemeManager.color(getContext(), R.attr.uiPrimary);
        int warning = getContext().getColor(R.color.status_warning);
        int danger = getContext().getColor(R.color.status_danger);
        if (ratio < 0.65) return blend(primary, warning, ratio / 0.65);
        return blend(warning, danger, (ratio - 0.65) / 0.35);
    }

    private int blend(int start, int end, double fraction) {
        double safe = Math.max(0, Math.min(1, fraction));
        return Color.rgb(
                (int) (Color.red(start) + (Color.red(end) - Color.red(start)) * safe),
                (int) (Color.green(start) + (Color.green(end) - Color.green(start)) * safe),
                (int) (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * safe)
        );
    }

    private float xOf(long timestamp, long start, long range, float left, float right) {
        return left + (right - left) * (timestamp - start) / (float) range;
    }

    private float yOf(
            double value, double chartMin, double range, float top, float bottom
    ) {
        return bottom - (float) ((value - chartMin) / range) * (bottom - top);
    }

    private double displayedValue(BalanceTrendPoint point) {
        return showAmount ? point.displayedAmount : point.displayedSurplus;
    }

    private double displayedRate(BalanceTrendPoint point) {
        return showAmount ? point.costPerHour : point.rateKwhPerHour;
    }

    private void updateContentDescription() {
        if (points.isEmpty()) {
            setContentDescription("暂无小时级历史数据");
            return;
        }
        BalanceTrendPoint latest = points.get(points.size() - 1);
        setContentDescription(String.format(
                Locale.CHINA,
                "最近 30 天余额和耗电速率，可向左滑动查看更早数据，最新余额 %.2f%s，共 %d 个小时采样",
                displayedValue(latest), showAmount ? "元" : "度", points.size()
        ));
    }

    /**
     * 纵轴和可滚动图表共用完全相同的范围计算，避免刻度值与折线位置不一致。
     * 返回数组依次为 chartMin、chartMax。
     */
    static double[] calculateValueScale(
            List<BalanceTrendPoint> source, boolean amount
    ) {
        if (source == null || source.isEmpty()) return new double[]{0.0, 1.0};
        double first = amount
                ? source.get(0).displayedAmount : source.get(0).displayedSurplus;
        double min = first;
        double max = first;
        for (BalanceTrendPoint point : source) {
            double value = amount ? point.displayedAmount : point.displayedSurplus;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        double rawRange = max - min;
        double padding = rawRange > 0.0001
                ? rawRange * 0.10 : Math.max(0.5, Math.abs(max) * 0.05);
        return new double[]{Math.max(0, min - padding), max + padding};
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
