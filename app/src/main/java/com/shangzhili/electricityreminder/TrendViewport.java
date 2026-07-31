package com.shangzhili.electricityreminder;

/** 余额趋势“30 天内容、7 天视口”的纯计算逻辑，便于不启动 Android 的单元测试。 */
final class TrendViewport {
    private static final double DAYS_PER_VIEWPORT = 7.0;

    private TrendViewport() {
    }

    static int contentWidth(int viewportWidth, double spanDays) {
        int safeViewport = Math.max(1, viewportWidth);
        double safeDays = Math.max(1.0, spanDays);
        return Math.max(
                safeViewport,
                Math.round((float) (
                        safeViewport * safeDays / DAYS_PER_VIEWPORT
                ))
        );
    }
}
