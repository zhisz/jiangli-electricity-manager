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
 * 固定在 30 天余额趋势左侧的纵轴。
 *
 * <p>趋势内容本身会放进 HorizontalScrollView 并横向滑动。如果把刻度也画在趋势 View
 * 内，用户一向左查看历史，纵轴数值就会离开屏幕。这里独立绘制最大值、中间值和最小值，
 * 并与 {@link ElectricityTrendView#calculateValueScale(List, boolean)} 共用范围算法。</p>
 */
public final class BalanceTrendAxisView extends View {
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<BalanceTrendPoint> points = new ArrayList<>();
    private boolean showAmount;

    public BalanceTrendAxisView(Context context, AttributeSet attrs) {
        super(context, attrs);
        axisPaint.setColor(context.getColor(R.color.border));
        axisPaint.setStrokeWidth(dp(1));
        textPaint.setColor(context.getColor(R.color.text_secondary));
        textPaint.setTextSize(dp(10));
        textPaint.setTextAlign(Paint.Align.RIGHT);
    }

    public void setData(List<BalanceTrendPoint> source) {
        points.clear();
        if (source != null) points.addAll(source);
        invalidate();
    }

    public void setShowAmount(boolean showAmount) {
        if (this.showAmount == showAmount) return;
        this.showAmount = showAmount;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float top = dp(16);
        float bottom = getHeight() - dp(78);
        float axisX = getWidth() - dp(1);
        double[] scale = ElectricityTrendView.calculateValueScale(points, showAmount);

        canvas.drawLine(axisX, top, axisX, bottom, axisPaint);
        drawValue(canvas, scale[1], top + dp(4));
        drawValue(canvas, (scale[1] + scale[0]) / 2.0, (top + bottom) / 2f + dp(4));
        drawValue(canvas, scale[0], bottom);
    }

    private void drawValue(Canvas canvas, double value, float baseline) {
        String pattern = Math.abs(value) < 1.0 && value != 0.0 ? "%.2f" : "%.1f";
        canvas.drawText(
                String.format(Locale.CHINA, pattern, value),
                getWidth() - dp(6), baseline, textPaint
        );
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
