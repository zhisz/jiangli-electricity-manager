package com.shangzhili.electricityreminder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 固定在每日消耗柱状图左侧的纵轴。
 *
 * <p>该 View 不放进 HorizontalScrollView，因此用户左右查看 30/60 天柱子时，最大值、
 * 中间值和零点始终可见。它与 {@link DailyUsageBarView} 使用完全相同的最大值算法，
 * 避免纵轴数值和实际柱高不一致。</p>
 */
public final class DailyUsageAxisView extends View {
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<DailyUsagePoint> points = new ArrayList<>();

    public DailyUsageAxisView(Context context, AttributeSet attrs) {
        super(context, attrs);
        axisPaint.setColor(context.getColor(R.color.border));
        axisPaint.setStrokeWidth(dp(1));
        textPaint.setColor(context.getColor(R.color.text_secondary));
        textPaint.setTextSize(dp(10));
        textPaint.setTextAlign(Paint.Align.RIGHT);
    }

    public void setPoints(List<DailyUsagePoint> points) {
        this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float top = dp(20);
        float bottom = getHeight() - dp(32);
        float axisX = getWidth() - dp(1);
        double max = DailyUsageBarView.calculateAxisMax(points);

        canvas.drawLine(axisX, top, axisX, bottom, axisPaint);
        drawValue(canvas, max, top + dp(4));
        drawValue(canvas, max / 2.0, (top + bottom) / 2f + dp(4));
        drawValue(canvas, 0.0, bottom);
    }

    private void drawValue(Canvas canvas, double value, float baseline) {
        // 小于 1 度时保留两位小数，其余保留一位，在有限宽度内兼顾精度和可读性。
        String pattern = Math.abs(value) < 1.0 && value != 0.0 ? "%.2f" : "%.1f";
        canvas.drawText(String.format(Locale.CHINA, pattern, value),
                getWidth() - dp(6), baseline, textPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
