package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 回归跨版本升级判断，防止本地旧 APK 再次抢在远程最新版前安装。 */
public final class AppUpdateManagerTest {
    @Test
    public void newerRemoteVersionReplacesIntermediatePendingDownload() {
        assertTrue(AppUpdateManager.shouldReplacePending(34, 36));
    }

    @Test
    public void sameOrOlderRemoteVersionKeepsExistingDownload() {
        assertFalse(AppUpdateManager.shouldReplacePending(36, 36));
        assertFalse(AppUpdateManager.shouldReplacePending(36, 35));
        assertFalse(AppUpdateManager.shouldReplacePending(0, 36));
    }
}
