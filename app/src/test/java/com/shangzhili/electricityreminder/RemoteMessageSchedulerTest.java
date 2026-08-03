package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 防止远程消息检查间隔被误改回数小时，导致公告只能等用户重新打开 App。 */
public final class RemoteMessageSchedulerTest {
    @Test public void remoteMessagesAreCheckedEveryFifteenMinutes() {
        assertEquals(15 * 60_000L, RemoteMessageScheduler.INTERVAL_MILLIS);
    }
}
