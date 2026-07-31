package com.shangzhili.electricityreminder;

/**
 * 余额趋势专用的分段低通平滑器。
 *
 * <p>它只改变屏幕上的绘制控制点，不修改数据库、平台采样或累计用量。算法使用
 * 1/2/3/2/1 三角权重做两轮轻量滤波，可消除小时采样形成的细碎锯齿；充值跳升被视为
 * 硬边界，边界两侧绝不互相取样，因此充值不会再次被摊成数小时或数日的缓慢上涨。</p>
 */
final class TrendSmoother {
    private TrendSmoother() {
    }

    static double[] smooth(double[] source, boolean[] jumpBefore, int passes) {
        if (source == null) return new double[0];
        double[] result = source.clone();
        if (source.length < 3 || jumpBefore == null || jumpBefore.length != source.length) {
            return result;
        }
        int safePasses = Math.max(0, Math.min(3, passes));
        for (int pass = 0; pass < safePasses; pass++) {
            double[] next = result.clone();
            int segmentStart = 0;
            for (int index = 1; index <= result.length; index++) {
                boolean segmentEnds = index == result.length || jumpBefore[index];
                if (!segmentEnds) continue;
                smoothSegment(result, next, segmentStart, index);
                segmentStart = index;
            }
            result = next;
        }
        return result;
    }

    private static void smoothSegment(
            double[] source, double[] target, int start, int endExclusive
    ) {
        int length = endExclusive - start;
        if (length < 3) return;
        // 段首段尾是平台校正或充值后的真实锚点，保留它们可以避免整体曲线漂移。
        for (int index = start + 1; index < endExclusive - 1; index++) {
            double weightedSum = 0;
            int weightSum = 0;
            for (int offset = -2; offset <= 2; offset++) {
                int sample = index + offset;
                if (sample < start || sample >= endExclusive) continue;
                int weight = 3 - Math.abs(offset);
                weightedSum += source[sample] * weight;
                weightSum += weight;
            }
            target[index] = weightSum == 0 ? source[index] : weightedSum / weightSum;
        }
    }
}
