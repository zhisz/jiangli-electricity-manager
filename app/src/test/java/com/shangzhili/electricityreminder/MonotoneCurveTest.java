package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 平滑曲线必须保留平台和局部极值，不能为了美观制造余额过冲。 */
public final class MonotoneCurveTest {
    @Test
    public void plateauAndDirectionChangeUseZeroTangent() {
        double[] tangent = MonotoneCurve.tangents(
                new double[]{0, 1, 2, 4},
                new double[]{10, 10, 8, 12}
        );
        assertEquals(0, tangent[0], 0.000001);
        assertEquals(0, tangent[1], 0.000001);
        assertEquals(0, tangent[2], 0.000001);
    }

    @Test
    public void unevenMonotoneSamplesKeepDirection() {
        double[] tangent = MonotoneCurve.tangents(
                new double[]{0, 1, 5, 8},
                new double[]{20, 18, 17, 12}
        );
        for (double value : tangent) {
            assertTrue("下降曲线的切线不应反向", value <= 0);
        }
    }

    @Test
    public void twoPointsUseExactSecant() {
        double[] tangent = MonotoneCurve.tangents(
                new double[]{2, 6},
                new double[]{3, 11}
        );
        assertEquals(2, tangent[0], 0.000001);
        assertEquals(2, tangent[1], 0.000001);
    }
}
