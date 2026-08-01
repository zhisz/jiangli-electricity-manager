package com.shangzhili.electricityreminder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 面向普通用户的余额估算趋势图。
 *
 * <p>平台阶梯采样仍由计算层完整保留，用于下一次余额变化后的累计量校正；但它不再直接
 * 绘制到界面，避免横线、竖线、虚线和采样点同时出现造成视觉噪声。页面只展示一条经过
 * 防过冲处理的估算曲线，尾端尚待平台更新时使用同色浅阴影表达不确定范围。</p>
 */
public final class ElectricityTrendView extends View {
    public interface OnTrendPointSelectedListener {
        void onSelected(BalanceTrendPoint point);
    }

    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint estimatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint confidencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path estimatePath = new Path();
    private final Path areaPath = new Path();
    private final Path confidencePath = new Path();
    private final SimpleDateFormat dayFormat =
            new SimpleDateFormat("MM/dd", Locale.CHINA);
    private final Date labelDate = new Date();
    private final List<BalanceTrendPoint> points = new ArrayList<>();
    private boolean showAmount;
    /** HorizontalScrollView 实际可见宽度，用于严格换算“一个视口约 7 天”。 */
    private int viewportWidth;
    private int selectedIndex = -1;
    /** 游标横坐标使用内容 View 坐标，拖动时不吸附，辅助线才能真正跟手。 */
    private float cursorX = Float.NaN;
    private float touchDownX;
    private float touchDownY;
    private boolean draggingCursor;
    private final int touchSlop;
    private OnTrendPointSelectedListener listener;

    public ElectricityTrendView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        int primary = AppThemeManager.color(context, R.attr.uiPrimary);
        axisPaint.setColor(context.getColor(R.color.border));
        axisPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(withAlpha(context.getColor(R.color.chart_grid), 0x78));
        gridPaint.setStrokeWidth(dp(0.75f));
        estimatePaint.setColor(primary);
        estimatePaint.setStyle(Paint.Style.STROKE);
        estimatePaint.setStrokeWidth(dp(2.25f));
        estimatePaint.setStrokeCap(Paint.Cap.ROUND);
        estimatePaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setColor(withAlpha(primary, 0x24));
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(6.5f));
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        confidencePaint.setColor(withAlpha(
                primary, 0x14
        ));
        confidencePaint.setStyle(Paint.Style.FILL);
        selectionPaint.setColor(withAlpha(primary, 0x66));
        selectionPaint.setStrokeWidth(dp(1.5f));
        cursorLabelPaint.setColor(withAlpha(primary, 0xEE));
        cursorLabelPaint.setStyle(Paint.Style.FILL);
        cursorValuePaint.setColor(Color.WHITE);
        cursorValuePaint.setTextSize(dp(11.5f));
        cursorValuePaint.setTypeface(Typeface.DEFAULT_BOLD);
        cursorValuePaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(context.getColor(R.color.text_secondary));
        textPaint.setTextSize(dp(10.5f));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        int primary = AppThemeManager.color(getContext(), R.attr.uiPrimary);
        areaPaint.setShader(new LinearGradient(
                0, dp(18), 0, Math.max(dp(19), height - dp(30)),
                withAlpha(primary, 0x1C), withAlpha(primary, 0x01),
                Shader.TileMode.CLAMP
        ));
        areaPaint.setStyle(Paint.Style.FILL);
    }

    public void setData(
            List<BalanceTrendPoint> trendPoints,
            List<RechargeRecord> ignoredRechargeRecords
    ) {
        points.clear();
        if (trendPoints != null) points.addAll(trendPoints);
        // 首次进入或数据刷新后默认选中最新点；具体横坐标需等 View 完成测量后才能确定。
        selectedIndex = TrendCursorMath.latestIndex(points.size());
        cursorX = Float.NaN;
        updateContentDescription();
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

    public void setViewportWidth(int viewportWidth) {
        int safeWidth = Math.max(0, viewportWidth);
        if (this.viewportWidth == safeWidth) return;
        this.viewportWidth = safeWidth;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        long spanMillis = points.size() < 2 ? 0
                : points.get(points.size() - 1).reading.timestamp
                - points.get(0).reading.timestamp;
        double spanDays = Math.max(1.0, spanMillis / (24.0 * 3_600_000.0));
        /*
         * 总内容宽度按“实际天数 ÷ 7 × 视口宽度”计算。30 天约为 4.29 屏；
         * 数据不足 7 天时至少铺满一屏，不产生无意义的横向空白。
         */
        int baseViewport = viewportWidth > 0 ? viewportWidth : Math.round(dp(320));
        int desiredWidth = TrendViewport.contentWidth(baseViewport, spanDays);
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
        float top = dp(18);
        float bottom = getHeight() - dp(30);
        long startTime = points.get(0).reading.timestamp;
        long endTime = points.get(points.size() - 1).reading.timestamp;
        long timeRange = Math.max(1, endTime - startTime);
        double[] scale = calculateValueScale(points, showAmount);
        double range = Math.max(0.5, scale[1] - scale[0]);

        for (int level = 0; level <= 2; level++) {
            float y = top + (bottom - top) * level / 2f;
            canvas.drawLine(left, y, right, y, gridPaint);
        }
        canvas.drawLine(left, bottom, right, bottom, axisPaint);

        float[] x = new float[points.size()];
        double[] rawValues = new double[points.size()];
        boolean[] jumpBefore = new boolean[points.size()];
        for (int i = 0; i < points.size(); i++) {
            rawValues[i] = estimatedValue(points.get(i));
            if (i > 0) jumpBefore[i] = isUpwardJump(
                    points.get(i - 1), points.get(i)
            );
        }
        /*
         * 两轮三角低通只作用于同一连续消费段。充值边界不会参与相邻平均，因此视觉更
         * 柔和的同时仍然保持“一次跳升”，不会重现数日缓慢上涨。
         */
        double[] smoothedValues = TrendSmoother.smooth(rawValues, jumpBefore, 2);
        float[] estimateY = new float[points.size()];
        for (int i = 0; i < points.size(); i++) {
            BalanceTrendPoint point = points.get(i);
            x[i] = xOf(point.reading.timestamp, startTime, timeRange, left, right);
            estimateY[i] = yOf(smoothedValues[i], scale[0], range, top, bottom);
        }

        buildEstimatePath(x, estimateY, jumpBefore);
        areaPath.set(estimatePath);
        areaPath.lineTo(right, bottom);
        areaPath.lineTo(left, bottom);
        areaPath.close();
        canvas.drawPath(areaPath, areaPaint);
        drawConfidenceBand(canvas, x, scale[0], range, top, bottom);
        canvas.drawPath(estimatePath, glowPaint);
        canvas.drawPath(estimatePath, estimatePaint);

        /*
         * setData 时内容宽度尚未测量，不能提前计算最右侧坐标。第一次绘制已经拥有可靠
         * 的 right，此时再初始化游标，进入页面即可看到最新趋势值和辅助线。
         */
        if (selectedIndex >= 0 && !Float.isFinite(cursorX)) cursorX = right;
        if (selectedIndex >= 0 && selectedIndex < points.size() && Float.isFinite(cursorX)) {
            float activeX = Math.max(left, Math.min(right, cursorX));
            double activeValue = TrendCursorMath.interpolate(activeX, x, smoothedValues);
            float activeY = yOf(activeValue, scale[0], range, top, bottom);
            canvas.drawLine(activeX, top, activeX, bottom, selectionPaint);
            canvas.drawCircle(activeX, activeY, dp(5.5f), selectionPaint);
            drawCursorValue(canvas, activeX, activeY, activeValue, left, right, top, bottom);
        }
        drawTimeLabels(canvas, startTime, endTime, left, right);
    }

    /**
     * 下降区间使用防过冲 PCHIP；余额上涨则在服务器给出的“变化发生区间”内画一次跳升。
     * 这样充值前后的趋势仍然连续，却不会把一次充值错误平滑成连续数日缓慢上涨。
     */
    private void buildEstimatePath(float[] x, float[] y, boolean[] jumpBefore) {
        estimatePath.reset();
        estimatePath.moveTo(x[0], y[0]);
        int smoothStart = 0;
        for (int index = 1; index < points.size(); index++) {
            if (!jumpBefore[index]) continue;

            appendSmoothRange(x, y, smoothStart, index);
            float jumpX = jumpX(index, x);
            float gap = Math.max(0, x[index] - x[index - 1]);
            float halfWidth = Math.min(
                    dp(7), Math.max(dp(1.25f), gap * 0.08f)
            );
            float transitionStart = Math.max(x[index - 1], jumpX - halfWidth);
            float transitionEnd = Math.min(x[index], jumpX + halfWidth);
            float transitionWidth = Math.max(dp(0.5f), transitionEnd - transitionStart);
            estimatePath.lineTo(transitionStart, y[index - 1]);
            /*
             * 极短的 S 形贝塞尔替代生硬直角：视觉上仍是一瞬间充值，但在高分辨率屏幕
             * 上没有锯齿和尖锐拐点。过渡宽度最多 14dp，不会被误读为持续用电上涨。
             */
            estimatePath.cubicTo(
                    transitionStart + transitionWidth * 0.38f, y[index - 1],
                    transitionEnd - transitionWidth * 0.38f, y[index],
                    transitionEnd, y[index]
            );
            estimatePath.lineTo(x[index], y[index]);
            smoothStart = index;
        }
        appendSmoothRange(x, y, smoothStart, points.size());
    }

    /**
     * Path 已经位于 start 点，本方法只追加三次曲线，不重新 moveTo，保证多个下降段和
     * 中间的充值跳升属于同一条可填充路径。
     */
    private void appendSmoothRange(float[] x, float[] y, int start, int endExclusive) {
        int count = endExclusive - start;
        if (count <= 1) return;
        double[] sx = new double[count];
        double[] sy = new double[count];
        for (int i = 0; i < count; i++) {
            sx[i] = x[start + i];
            sy[i] = y[start + i];
        }
        double[] tangent = MonotoneCurve.tangents(sx, sy);
        for (int i = 0; i < count - 1; i++) {
            double width = sx[i + 1] - sx[i];
            if (width <= 0) {
                estimatePath.lineTo((float) sx[i + 1], (float) sy[i + 1]);
            } else {
                estimatePath.cubicTo(
                        (float) (sx[i] + width / 3), (float) (sy[i] + tangent[i] * width / 3),
                        (float) (sx[i + 1] - width / 3),
                        (float) (sy[i + 1] - tangent[i + 1] * width / 3),
                        (float) sx[i + 1], (float) sy[i + 1]
                );
            }
        }
    }

    private boolean isUpwardJump(
            BalanceTrendPoint previous,
            BalanceTrendPoint current
    ) {
        double before = estimatedValue(previous);
        double after = estimatedValue(current);
        if (after <= before + 0.01) return false;
        return current.reading.isRechargeChange()
                || current.rechargeCount > 0
                || current.unmatchedIncrease;
    }

    /**
     * 服务器只知道变化发生在 previous_query_time～current_query_time，不能冒充准确时刻。
     * 视觉跳点放在该区间中点；若事件来自本地或时间字段缺失，则使用相邻采样中点。
     */
    private float jumpX(int index, float[] x) {
        HistoryPoint current = points.get(index).reading;
        HistoryPoint previous = points.get(index - 1).reading;
        long start = current.changeStartTimestamp > 0
                ? Math.max(previous.timestamp, current.changeStartTimestamp)
                : previous.timestamp;
        long midpoint = start + Math.max(0, current.timestamp - start) / 2;
        double fraction = current.timestamp <= previous.timestamp ? 0.5
                : (midpoint - previous.timestamp)
                / (double) (current.timestamp - previous.timestamp);
        fraction = Math.max(0.15, Math.min(0.85, fraction));
        return x[index - 1] + (x[index] - x[index - 1]) * (float) fraction;
    }

    /** 尾部预测上下界形成闭合半透明带；确认窗口没有阴影，避免制造虚假精度。 */
    private void drawConfidenceBand(
            Canvas canvas, float[] x, double min, double range, float top, float bottom
    ) {
        int first = firstForecastIndex();
        if (first < 0) return;
        int anchor = Math.max(0, first - 1);
        confidencePath.reset();
        confidencePath.moveTo(x[anchor], yOf(
                estimatedValue(points.get(anchor)), min, range, top, bottom
        ));
        for (int i = first; i < points.size(); i++) {
            confidencePath.lineTo(x[i], yOf(
                    confidenceHighValue(points.get(i)), min, range, top, bottom
            ));
        }
        for (int i = points.size() - 1; i >= first; i--) {
            confidencePath.lineTo(x[i], yOf(
                    confidenceLowValue(points.get(i)), min, range, top, bottom
            ));
        }
        confidencePath.close();
        canvas.drawPath(confidencePath, confidencePaint);
    }

    private int firstForecastIndex() {
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).awaitingSourceUpdate && points.get(i).rateValid) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (points.isEmpty()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                /*
                 * 只有从既有辅助线左右 28dp 内按下才锁定游标。没有命中时不禁止
                 * HorizontalScrollView 拦截，用户仍能自然地左右浏览完整 30 天时间轴。
                 */
                draggingCursor = TrendCursorMath.isDragStart(
                        event.getX(), cursorX, dp(28)
                );
                if (draggingCursor) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    moveCursorTo(event.getX(), true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggingCursor) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    moveCursorTo(event.getX(), true);
                }
                // 非游标手势继续允许父级在超过 touchSlop 后接管横向滚动。
                return true;
            case MotionEvent.ACTION_UP:
                if (draggingCursor) {
                    moveCursorTo(event.getX(), true);
                    finishCursorGesture();
                    return true;
                }
                float moved = (float) Math.hypot(
                        event.getX() - touchDownX, event.getY() - touchDownY
                );
                if (moved <= touchSlop) {
                    performClick();
                    // 普通点击仍吸附到最近采样点，随后可从辅助线附近开始连续拖动。
                    moveCursorTo(event.getX(), false);
                    notifySelection();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishCursorGesture();
                return true;
            default:
                return true;
        }
    }

    /**
     * 拖动时 cursorX 保持手指的连续位置；普通点击则放到最近真实采样点上。
     * selectedIndex 始终同步最近采样，用于无障碍播报及兼容原有监听接口。
     */
    private void moveCursorTo(float requestedX, boolean continuous) {
        float left = dp(8);
        float right = getWidth() - dp(10);
        float safeX = Math.max(left, Math.min(right, requestedX));
        selectedIndex = nearestPointIndex(safeX, left, right);
        if (continuous) {
            cursorX = safeX;
        } else {
            long start = points.get(0).reading.timestamp;
            long end = points.get(points.size() - 1).reading.timestamp;
            cursorX = xOf(
                    points.get(selectedIndex).reading.timestamp,
                    start, Math.max(1, end - start), left, right
            );
        }
        invalidate();
    }

    private int nearestPointIndex(float x, float left, float right) {
        float fraction = Math.max(0, Math.min(1, (x - left) / Math.max(1, right - left)));
        long start = points.get(0).reading.timestamp;
        long end = points.get(points.size() - 1).reading.timestamp;
        long target = start + (long) ((end - start) * fraction);
        int nearest = 0;
        long distance = Long.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            long next = Math.abs(points.get(i).reading.timestamp - target);
            if (next < distance) {
                distance = next;
                nearest = i;
            }
        }
        return nearest;
    }

    private void finishCursorGesture() {
        if (draggingCursor) notifySelection();
        draggingCursor = false;
        getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void notifySelection() {
        if (selectedIndex < 0 || selectedIndex >= points.size()) return;
        if (listener != null) listener.onSelected(points.get(selectedIndex));
        setContentDescription(String.format(
                Locale.CHINA, "当前趋势值 %.2f%s",
                estimatedValue(points.get(selectedIndex)), showAmount ? "元" : "度"
        ));
    }

    /**
     * 数值标签跟随曲线交点移动，并在左右、上下边缘自动避让。深紫底与白字保持清晰，
     * 但尺寸仅容纳一个数值，不在图中重新堆叠日期和说明文字。
     */
    private void drawCursorValue(
            Canvas canvas, float x, float y, double value,
            float left, float right, float top, float bottom
    ) {
        String label = String.format(
                Locale.CHINA, "%.2f %s", value, showAmount ? "元" : "度"
        );
        float horizontalPadding = dp(9);
        float bubbleWidth = cursorValuePaint.measureText(label) + horizontalPadding * 2;
        float bubbleHeight = dp(28);
        float bubbleLeft = Math.max(
                left, Math.min(right - bubbleWidth, x - bubbleWidth / 2)
        );
        float bubbleTop = y - bubbleHeight - dp(10);
        if (bubbleTop < top) bubbleTop = Math.min(bottom - bubbleHeight, y + dp(10));
        RectF bubble = new RectF(
                bubbleLeft, bubbleTop, bubbleLeft + bubbleWidth, bubbleTop + bubbleHeight
        );
        canvas.drawRoundRect(bubble, dp(9), dp(9), cursorLabelPaint);
        Paint.FontMetrics metrics = cursorValuePaint.getFontMetrics();
        float baseline = bubble.centerY() - (metrics.ascent + metrics.descent) / 2;
        canvas.drawText(label, bubble.centerX(), baseline, cursorValuePaint);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void drawTimeLabels(
            Canvas canvas, long startTime, long endTime, float left, float right
    ) {
        /*
         * 30 天数据会铺在约 4.3 个屏幕内，因此每一天的刻度本身并不拥挤；真正的重叠
         * 来自起止位置使用较长的“日期+时间”，又在它旁边继续绘制每日日期。这里按
         * 实际文字宽度设置最小间隔：只抽稀横轴标签，不删除任何采样点或趋势曲线。
         */
        float baseline = getHeight() - dp(8);
        float minGap = Math.max(dp(52), textPaint.measureText("00/00") + dp(18));

        labelDate.setTime(startTime);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(dayFormat.format(labelDate), left, baseline, textPaint);

        float lastLabelX = left;
        long day = 24L * 60 * 60 * 1_000;
        for (long timestamp = startTime + day;
             timestamp < endTime;
             timestamp += day) {
            float x = xOf(
                    timestamp, startTime, Math.max(1, endTime - startTime), left, right
            );
            // 给最右侧日期预留一个完整标签间距，避免最后两个刻度在边界处相互覆盖。
            if (x - lastLabelX < minGap || right - x < minGap) continue;
            labelDate.setTime(timestamp);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(dayFormat.format(labelDate), x, baseline, textPaint);
            lastLabelX = x;
        }

        // 时间跨度很短时起点标签已经足够；空间充足时才补画终点，彻底消除覆盖。
        if (right - lastLabelX >= minGap) {
            labelDate.setTime(endTime);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(dayFormat.format(labelDate), right, baseline, textPaint);
        }
    }

    private float xOf(long timestamp, long start, long range, float left, float right) {
        return left + (right - left) * (timestamp - start) / (float) range;
    }

    private float yOf(double value, double min, double range, float top, float bottom) {
        return bottom - (float) ((value - min) / range) * (bottom - top);
    }

    private double estimatedValue(BalanceTrendPoint point) {
        return showAmount ? point.displayedAmount : point.displayedSurplus;
    }

    private double confidenceLowValue(BalanceTrendPoint point) {
        return showAmount ? point.confidenceLowAmount : point.confidenceLowSurplus;
    }

    private double confidenceHighValue(BalanceTrendPoint point) {
        return showAmount ? point.confidenceHighAmount : point.confidenceHighSurplus;
    }

    private void updateContentDescription() {
        if (points.isEmpty()) {
            setContentDescription("暂无小时级历史数据");
            return;
        }
        BalanceTrendPoint latest = points.get(points.size() - 1);
        setContentDescription(String.format(
                Locale.CHINA,
                "估算余额趋势，可向左滑动查看更早数据，最新趋势值 %.2f%s",
                estimatedValue(latest), showAmount ? "元" : "度"
        ));
    }

    /**
     * 固定纵轴与可滚动图表共用范围。平台原始阶梯不参与可视范围，避免少量平台修正
     * 把主要趋势压缩；范围只容纳估算线和尾端置信区间。
     */
    static double[] calculateValueScale(List<BalanceTrendPoint> source, boolean amount) {
        if (source == null || source.isEmpty()) return new double[]{0, 1};
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (BalanceTrendPoint point : source) {
            double[] values = amount
                    ? new double[]{point.displayedAmount,
                    point.confidenceLowAmount, point.confidenceHighAmount}
                    : new double[]{point.displayedSurplus,
                    point.confidenceLowSurplus, point.confidenceHighSurplus};
            for (double value : values) {
                if (!Double.isFinite(value)) continue;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) return new double[]{0, 1};
        double raw = max - min;
        double padding = raw > 0.0001 ? raw * 0.10 : Math.max(0.5, Math.abs(max) * 0.05);
        return new double[]{Math.max(0, min - padding), max + padding};
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
