package com.shangzhili.electricityreminder;

import java.util.List;

/**
 * 把“下载”和“写入”组合成可单元测试的静默回退边界。
 *
 * <p>任何服务器、解析或本地云端合并异常都在此转为 0 条导入；调用方已有的实时查询
 * 结果、本地历史和提醒状态不会被回滚或改写。</p>
 */
final class CloudHistoryMergeRunner {
    interface Fetcher {
        List<CloudHistoryRecord> fetch() throws Exception;
    }

    interface Merger {
        int merge(List<CloudHistoryRecord> records) throws Exception;
    }

    private CloudHistoryMergeRunner() {
    }

    static int run(Fetcher fetcher, Merger merger) {
        try {
            return merger.merge(fetcher.fetch());
        } catch (Exception ignored) {
            return 0;
        }
    }
}
