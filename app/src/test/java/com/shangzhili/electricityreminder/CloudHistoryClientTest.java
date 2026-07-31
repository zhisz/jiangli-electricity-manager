package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 云端接口解析和离线回退测试；不启动 Android、不连接真实服务器。 */
public final class CloudHistoryClientTest {
    private static final String ROOM = "001001001001001";

    @Test
    public void parserOnlyAcceptsSuccessfulExpectedRoomRecords() throws Exception {
        String json = "{"
                + "\"dataVersion\":1,"
                + "\"timezone\":\"Asia/Shanghai\","
                + "\"records\":["
                + "{\"sampleKey\":\"valid\",\"roomCode\":\"" + ROOM + "\","
                + "\"queriedAt\":\"2026-07-31T08:02:00+08:00\","
                + "\"balanceKwh\":52.5,\"amountYuan\":31.5,\"queryResult\":\"success\"},"
                + "{\"sampleKey\":\"failure\",\"roomCode\":\"" + ROOM + "\","
                + "\"queriedAt\":\"2026-07-31T09:02:00+08:00\","
                + "\"balanceKwh\":null,\"amountYuan\":null,\"queryResult\":\"failure\"},"
                + "{\"sampleKey\":\"other\",\"roomCode\":\"001001001001002\","
                + "\"queriedAt\":\"2026-07-31T10:02:00+08:00\","
                + "\"balanceKwh\":20,\"amountYuan\":12,\"queryResult\":\"success\"}"
                + "]}";
        List<CloudHistoryRecord> result = CloudHistoryClient.parse(json, ROOM);
        assertEquals(1, result.size());
        assertEquals("valid", result.get(0).sampleKey);
        assertEquals(52.5, result.get(0).surplus, 0.0001);
    }

    @Test
    public void serverUnavailableDoesNotCallMergerOrEscapeToApp() {
        AtomicBoolean mergerCalled = new AtomicBoolean(false);
        int imported = CloudHistoryMergeRunner.run(
                () -> {
                    throw new IOException("server offline");
                },
                records -> {
                    mergerCalled.set(true);
                    return records.size();
                }
        );
        assertEquals(0, imported);
        assertFalse(mergerCalled.get());
    }

    @Test
    public void malformedCloudDataFallsBackWithoutDeletingLocalState() {
        AtomicBoolean localStateUntouched = new AtomicBoolean(true);
        int imported = CloudHistoryMergeRunner.run(
                () -> CloudHistoryClient.parse("{broken", ROOM),
                records -> {
                    localStateUntouched.set(false);
                    return records.size();
                }
        );
        assertEquals(0, imported);
        // 下载失败时合并器完全不会执行，因此不存在删除或覆盖本地记录的机会。
        assertEquals(true, localStateUntouched.get());
    }

    @Test
    public void emptyCloudPageIsAValidNoOp() {
        int imported = CloudHistoryMergeRunner.run(
                Collections::emptyList,
                List::size
        );
        assertEquals(0, imported);
    }
}
