package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 验证视觉平滑不会跨越充值边界，也不会移动整段首尾锚点。 */
public final class TrendSmootherTest {
    @Test
    public void smoothingReducesInteriorSpikeAndKeepsEndpoints() {
        double[] source = {100, 99, 99, 94, 93, 92};
        double[] smoothed = TrendSmoother.smooth(
                source, new boolean[source.length], 2
        );

        assertEquals(100, smoothed[0], 0.0001);
        assertEquals(92, smoothed[5], 0.0001);
        assertTrue(smoothed[3] > source[3]);
        assertTrue(Math.abs(smoothed[3] - smoothed[2])
                < Math.abs(source[3] - source[2]));
    }

    @Test
    public void rechargeBoundaryNeverBleedsIntoPreviousSegment() {
        double[] source = {100, 98, 96, 126, 124, 122};
        boolean[] jumpBefore = {false, false, false, true, false, false};
        double[] smoothed = TrendSmoother.smooth(source, jumpBefore, 2);

        assertEquals(96, smoothed[2], 0.0001);
        assertEquals(126, smoothed[3], 0.0001);
        assertEquals(30, smoothed[3] - smoothed[2], 0.0001);
    }
}
