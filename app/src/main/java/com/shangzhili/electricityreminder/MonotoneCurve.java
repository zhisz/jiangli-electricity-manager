package com.shangzhili.electricityreminder;

/**
 * 为不等间隔采样计算单调三次 Hermite 曲线的切线。
 *
 * <p>普通 Catmull-Rom 或贝塞尔平滑会在余额突然上涨、下降时越过真实最大/最小值，产生
 * 并不存在的负余额或额外峰值。本实现采用 Fritsch-Carlson/PCHIP 的加权调和斜率：
 * 同方向区间平滑连接，遇到平台或方向改变时把切线收紧为 0，从数学上限制过冲。</p>
 */
public final class MonotoneCurve {
    private MonotoneCurve() {}

    public static double[] tangents(double[] x, double[] y) {
        if (x == null || y == null || x.length != y.length) {
            throw new IllegalArgumentException("坐标数量必须一致");
        }
        int count = x.length;
        double[] tangent = new double[count];
        if (count < 2) return tangent;

        double[] width = new double[count - 1];
        double[] slope = new double[count - 1];
        for (int index = 0; index < count - 1; index++) {
            width[index] = x[index + 1] - x[index];
            if (width[index] <= 0) {
                // 重复或乱序时间不参与斜率推断；绘图层仍会保留对应实际点。
                slope[index] = 0;
            } else {
                slope[index] = (y[index + 1] - y[index]) / width[index];
            }
        }
        if (count == 2) {
            tangent[0] = slope[0];
            tangent[1] = slope[0];
            return tangent;
        }

        tangent[0] = endpointSlope(
                width[0], width[1], slope[0], slope[1]
        );
        tangent[count - 1] = endpointSlope(
                width[count - 2], width[count - 3],
                slope[count - 2], slope[count - 3]
        );
        for (int index = 1; index < count - 1; index++) {
            double previous = slope[index - 1];
            double next = slope[index];
            if (previous == 0 || next == 0 || Math.signum(previous) != Math.signum(next)) {
                tangent[index] = 0;
                continue;
            }
            double previousWidth = width[index - 1];
            double nextWidth = width[index];
            double firstWeight = 2 * nextWidth + previousWidth;
            double secondWeight = nextWidth + 2 * previousWidth;
            tangent[index] = (firstWeight + secondWeight)
                    / (firstWeight / previous + secondWeight / next);
        }
        return tangent;
    }

    /** PCHIP 端点限制，防止首尾控制点越过相邻真实值。 */
    private static double endpointSlope(
            double firstWidth,
            double secondWidth,
            double firstSlope,
            double secondSlope
    ) {
        if (firstWidth <= 0 || secondWidth <= 0 || firstSlope == 0) return 0;
        double value = ((2 * firstWidth + secondWidth) * firstSlope
                - firstWidth * secondSlope) / (firstWidth + secondWidth);
        if (Math.signum(value) != Math.signum(firstSlope)) return 0;
        if (Math.signum(firstSlope) != Math.signum(secondSlope)
                && Math.abs(value) > Math.abs(3 * firstSlope)) {
            return 3 * firstSlope;
        }
        return value;
    }
}
