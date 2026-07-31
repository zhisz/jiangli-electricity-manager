package com.shangzhili.electricityreminder;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 云端公共历史的“尽力而为”同步器。
 *
 * <p>同步完全独立于校付宝实时查询、SQLite 本地写入、图表读取和提醒调度。服务器离线、
 * 超时、返回 5xx 或 JSON 损坏时，异常只会在调试包写日志；不会回调失败、弹窗或清空
 * 本地数据。成功导入后才通知页面刷新图表。</p>
 */
public final class CloudHistorySynchronizer {
    private static final String TAG = "CloudHistorySync";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface OnHistoryMergedListener {
        void onHistoryMerged(int importedCount);
    }

    private CloudHistorySynchronizer() {
    }

    public static void sync(
            Context context,
            String roomId,
            String roomCode,
            OnHistoryMergedListener listener
    ) {
        String baseUrl = BuildConfig.ELEC_SERVICE_BASE_URL.trim();
        if (baseUrl.isEmpty() || roomId == null || roomCode == null) return;
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try (ReadingHistoryStore store = new ReadingHistoryStore(appContext)) {
                long latest = store.latestCloudTimestamp(roomId);
                // 向前重叠一分钟，可覆盖服务器同一时间点的延迟修正；cloud_sample_key
                // 唯一索引会消除重复，不会生成双份曲线点。
                long since = latest <= 0 ? 0 : Math.max(0, latest - 60_000);
                int imported = CloudHistoryMergeRunner.run(
                        () -> new CloudHistoryClient().fetch(baseUrl, roomCode, since),
                        records -> store.mergeCloudHistory(roomId, roomCode, records)
                );
                if (imported > 0 && listener != null) listener.onHistoryMerged(imported);
            } catch (Exception exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "云端公共历史不可用，继续使用本地数据", exception);
                }
            }
        });
    }
}
