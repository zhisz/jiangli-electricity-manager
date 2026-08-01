package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 回归跟手抽屉松手后的速度优先和距离兜底规则。 */
public final class DrawerGestureMathTest {
    @Test public void slowGestureUsesVisibleProgress() {
        assertFalse(DrawerGestureMath.shouldOpen(.44f, 10f, 50f));
        assertTrue(DrawerGestureMath.shouldOpen(.45f, 10f, 50f));
    }

    @Test public void flingDirectionOverridesProgress() {
        assertTrue(DrawerGestureMath.shouldOpen(.05f, 200f, 50f));
        assertFalse(DrawerGestureMath.shouldOpen(.95f, -200f, 50f));
    }

    @Test public void translationNeverEscapesDrawerBounds() {
        assertEquals(-500f, DrawerGestureMath.clampTranslation(-700f, 500f), 0f);
        assertEquals(0f, DrawerGestureMath.clampTranslation(20f, 500f), 0f);
        assertEquals(-180f, DrawerGestureMath.clampTranslation(-180f, 500f), 0f);
    }
}
