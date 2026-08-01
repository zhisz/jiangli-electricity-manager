package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TrendCursorMathTest {
    @Test
    public void defaultCursorSelectsLatestPoint() {
        assertEquals(4, TrendCursorMath.latestIndex(5));
        assertEquals(0, TrendCursorMath.latestIndex(1));
        assertEquals(-1, TrendCursorMath.latestIndex(0));
    }

    @Test
    public void dragStartsOnlyNearExistingCursor() {
        assertTrue(TrendCursorMath.isDragStart(118f, 100f, 28f));
        assertTrue(TrendCursorMath.isDragStart(72f, 100f, 28f));
        assertFalse(TrendCursorMath.isDragStart(71.9f, 100f, 28f));
        assertFalse(TrendCursorMath.isDragStart(100f, Float.NaN, 28f));
    }

    @Test
    public void valueFollowsCursorBetweenPoints() {
        float[] x = {10f, 30f, 50f};
        double[] value = {80, 70, 50};
        assertEquals(75, TrendCursorMath.interpolate(20f, x, value), 0.0001);
        assertEquals(60, TrendCursorMath.interpolate(40f, x, value), 0.0001);
    }

    @Test
    public void interpolationClampsOutsideChart() {
        float[] x = {10f, 30f};
        double[] value = {80, 70};
        assertEquals(80, TrendCursorMath.interpolate(-20f, x, value), 0.0001);
        assertEquals(70, TrendCursorMath.interpolate(80f, x, value), 0.0001);
    }
}
