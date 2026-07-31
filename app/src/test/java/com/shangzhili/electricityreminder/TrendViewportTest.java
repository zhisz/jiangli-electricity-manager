package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 防止后续再次把“7 天可见宽度”误改成“只加载 7 天数据”。 */
public final class TrendViewportTest {
    @Test
    public void thirtyDaysOccupyAboutFourPointTwoNineScreens() {
        assertEquals(1_286, TrendViewport.contentWidth(300, 30));
    }

    @Test
    public void fewerThanSevenDaysStillFillOneScreen() {
        assertEquals(300, TrendViewport.contentWidth(300, 3));
        assertEquals(300, TrendViewport.contentWidth(300, 7));
    }
}
