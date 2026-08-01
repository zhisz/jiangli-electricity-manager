package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 回归新房间的低余额复查默认值，避免以后再次误改回旧版 48 小时。 */
public final class AppConfigDefaultsTest {
    @Test
    public void newRoomRepeatCheckDefaultsToSixtyMinutes() {
        assertEquals(60.0, AppConfig.DEFAULT_REPEAT_MINUTES, 0.0);
    }
}
