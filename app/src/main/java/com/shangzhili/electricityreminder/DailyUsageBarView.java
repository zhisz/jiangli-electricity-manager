package com.shangzhili.electricityreminder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 适合放在 HorizontalScrollView 中的轻量每日用电柱状图。
 *
 * <p>每一天固定占用一个槽位，因此 60 根柱子不会被强行压缩到一屏；View 会按数据数量
 * 计算自身宽度，由外层容器负责横向滑动。点击和滑动通过 touchSlop 区分，拖动图表时不会
 * 误触柱子。</p>
 */
public final class DailyUsageBarView extends View {
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd", Locale.CHINA);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint missingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int touchSlop;
    private List<DailyUsagePoint> points = new ArrayList<>();
    private OnBarSelectedListener listener;
    private int selectedIndex = -1;
    private float downX;
    private float downY;
    private boolean moved;

    public DailyUsageBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
        axisPaint.setColor(context.getColor(R.color.border));
        axisPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(context.getColor(R.color.chart_grid));
        gridPaint.setStrokeWidth(dp(1));
        barPaint.setColor(AppThemeManager.color(context, R.attr.uiPrimary));
        selectedPaint.setColor(context.getColor(R.color.chart_recharge));
        missingPaint.setColor(context.getColor(R.color.chart_missing));
        missingPaint.setStrokeWidth(dp(2));
        textPaint.setColor(context.getColor(R.color.text_secondary));
        textPaint.setTextSize(dp(10));
    }

    public void setPoints(List<DailyUsagePoint> points) {
        this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);
        selectedIndex = -1;
        requestLayout();
        invalidate();
    }

    public void setOnBarSelectedListener(OnBarSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 纵轴已经移到 HorizontalScrollView 外部固定显示，这里只计算可滚动柱子区宽度。
        int desiredWidth = Math.round(dp(8) + points.size() * dp(38) + dp(12));
        int desiredHeight = Math.round(dp(220));
        setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(8);
        float top = dp(20);
        float bottom = getHeight() - dp(32);
        float slotWidth = dp(38);
        float barWidth = dp(20);
        float right = left + points.size() * slotWidth;
        // 网格跟随柱子滑动，但对应的三个纵轴刻度固定在左侧，不会因默认滚到最近日期而消失。
        for (int level = 0; level <= 2; level++) {
            float y = top + (bottom - top) * level / 2f;
            canvas.drawLine(left, y, right, y, level == 2 ? axisPaint : gridPaint);
        }
        double max = calculateAxisMax(points);

        for (int index = 0; index < points.size(); index++) {
            DailyUsagePoint point = points.get(index);
            float barLeft = left + index * slotWidth + (slotWidth - barWidth) / 2f;
            float barRight = barLeft + barWidth;
            if (point.hasData) {
                float height = (float) (point.usageKwh / max) * (bottom - top);
                // 真正的 0 用电仍画一条短柱，与“缺少数据”的灰色横线明确区分。
                height = Math.max(dp(2), height);
                canvas.drawRoundRect(
                        barLeft, bottom - height, barRight, bottom,
                        dp(3), dp(3), index == selectedIndex ? selectedPaint : barPaint
                );
            } else {
                canvas.drawLine(barLeft, bottom - dp(2), barRight, bottom - dp(2), missingPaint);
            }

            // 每 5 天标一个日期，最后一天始终标注；固定槽宽可避免标签互相覆盖。
            if (index % 5 == 0 || index == points.size() - 1) {
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(
                        DATE_FORMAT.format(point.date),
                        barLeft + barWidth / 2f, getHeight() - dp(10), textPaint
                );
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
                    moved = true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved && selectBarAt(event.getX())) performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean selectBarAt(float x) {
        if (x < dp(8)) return false;
        int index = (int) ((x - dp(8)) / dp(38));
        if (index < 0 || index >= points.size()) return false;
        selectedIndex = index;
        DailyUsagePoint point = points.get(index);
        setContentDescription(
                point.date + (point.hasData
                        ? String.format(Locale.CHINA, " 用电 %.2f 度，约 %.2f 元", point.usageKwh, point.costAmount)
                        : " 数据不足")
        );
        invalidate();
        if (listener != null) listener.onBarSelected(point);
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /**
     * 计算柱状图统一使用的“好看刻度”最大值。
     *
     * <p>例如真实最大值为 3.27 度时，纵轴显示到 5 度；真实最大值为 0.74 度时，
     * 显示到 1 度。柱子视图和固定纵轴都调用同一个方法，保证柱高与刻度严格对应。</p>
     */
    static double calculateAxisMax(List<DailyUsagePoint> points) {
        double rawMax = 0.0;
        if (points != null) {
            for (DailyUsagePoint point : points) {
                if (point.hasData) rawMax = Math.max(rawMax, point.usageKwh);
            }
        }
        if (rawMax <= 0.0) return 0.1;

        double magnitude = Math.pow(10.0, Math.floor(Math.log10(rawMax)));
        double normalized = rawMax / magnitude;
        double rounded;
        if (normalized <= 1.0) rounded = 1.0;
        else if (normalized <= 2.0) rounded = 2.0;
        else if (normalized <= 5.0) rounded = 5.0;
        else rounded = 10.0;
        return rounded * magnitude;
    }

    public interface OnBarSelectedListener {
        void onBarSelected(DailyUsagePoint point);
    }
}
