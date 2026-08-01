package com.shangzhili.electricityreminder;

/** 余额趋势游标的纯计算规则，独立于 Android 触摸框架，便于稳定回归手势边界。 */
final class TrendCursorMath {
    private TrendCursorMath() {}

    /** 有数据时默认选择最后一个趋势点；空数据保持未选中状态。 */
    static int latestIndex(int pointCount) {
        return pointCount > 0 ? pointCount - 1 : -1;
    }

    /** 只有按下点落在现有辅助线命中半径内，才允许把横滑解释为游标调整。 */
    static boolean isDragStart(float touchX, float cursorX, float hitRadius) {
        return Float.isFinite(cursorX)
                && hitRadius >= 0
                && Math.abs(touchX - cursorX) <= hitRadius;
    }

    /** 在相邻趋势点之间做线性视觉插值；越界时钳制到首尾值。 */
    static double interpolate(float targetX, float[] x, double[] values) {
        if (x == null || values == null || x.length == 0 || x.length != values.length) {
            throw new IllegalArgumentException("趋势坐标和值必须非空且长度一致");
        }
        if (targetX <= x[0]) return values[0];
        for (int i = 1; i < x.length; i++) {
            if (targetX > x[i]) continue;
            float width = Math.max(0.001f, x[i] - x[i - 1]);
            float fraction = (targetX - x[i - 1]) / width;
            return values[i - 1] + (values[i] - values[i - 1]) * fraction;
        }
        return values[values.length - 1];
    }
}
