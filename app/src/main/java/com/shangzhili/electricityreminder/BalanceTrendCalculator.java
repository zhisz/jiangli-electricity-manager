package com.shangzhili.electricityreminder;

import java.util.ArrayList;
import java.util.List;

/**
 * 将小时采样转换为“余额 + 延迟更新感知的时段耗电速率”。
 *
 * <p>校付宝余额并非实时结算。若接口连续返回 100、100、100、94，不能把前两小时画成
 * 零、最后一小时画成 6 度。本算法会等到 94 这一新值确认累计变化，再把 6 度按三个小时
 * 的实际时长均摊为约 2 度/小时，同时把估算余额画成 100、98、96、94。</p>
 *
 * <p>尚未等到下一次余额变化的尾部平台没有足够信息，明确标为“等待数据源结算”，不会
 * 猜测为零。充值仍按精确时间逐笔加入累计余额，所以同一天多笔充值可以落入各自时段。</p>
 */
public final class BalanceTrendCalculator {
    private static final double CHANGE_TOLERANCE = 0.005;
    private static final double INCREASE_TOLERANCE = 0.01;

    private BalanceTrendCalculator() {
    }

    public static List<BalanceTrendPoint> calculate(
            List<HistoryPoint> readings,
            List<RechargeRecord> recharges
    ) {
        List<BalanceTrendPoint> result = new ArrayList<>();
        if (readings == null || readings.isEmpty()) return result;
        List<RechargeRecord> safeRecharges =
                recharges == null ? new ArrayList<>() : recharges;

        HistoryPoint first = readings.get(0);
        result.add(new BalanceTrendPoint(
                first, first.surplus, first.amount, first.timestamp,
                0, 0, 0, 0, 0, 0,
                false, false, false, false, 0
        ));

        int blockStart = 0;
        for (int index = 1; index < readings.size(); index++) {
            if (!hasMeaningfulChange(readings.get(index - 1), readings.get(index))) {
                continue;
            }
            appendConfirmedBlock(
                    readings, safeRecharges, blockStart, index, result
            );
            blockStart = index;
        }
        appendAwaitingTail(readings, safeRecharges, blockStart, result);
        return result;
    }

    /**
     * 把“最后一次已变化读数 → 下一次变化读数”作为一个确认窗口，再按每个子区间的
     * 实际毫秒数分配累计消费。这样即使系统闹钟稍有延迟，总量和平均速率仍保持一致。
     */
    private static void appendConfirmedBlock(
            List<HistoryPoint> readings,
            List<RechargeRecord> recharges,
            int startIndex,
            int endIndex,
            List<BalanceTrendPoint> result
    ) {
        HistoryPoint start = readings.get(startIndex);
        HistoryPoint end = readings.get(endIndex);
        long durationMillis = end.timestamp - start.timestamp;
        double totalHours = durationMillis / 3_600_000.0;
        RechargeSummary totalRecharge = rechargeSummary(
                recharges, start.timestamp, end.timestamp
        );
        double amountDrop = start.amount + totalRecharge.amount - end.amount;
        double surplusDrop = start.surplus - end.surplus;
        boolean unmatchedIncrease = (totalRecharge.amount <= 0
                && surplusDrop < -INCREASE_TOLERANCE)
                || (totalRecharge.amount > 0 && amountDrop < -INCREASE_TOLERANCE);
        double unitPrice = inferredUnitPrice(start, end);
        boolean valid = totalHours > 0.01 && !unmatchedIncrease;

        double totalCost = valid ? Math.max(0, amountDrop) : 0;
        double totalUsage = 0;
        if (valid) {
            if (totalRecharge.amount > 0) {
                if (unitPrice <= 0) valid = false;
                else totalUsage = totalCost / unitPrice;
            } else {
                totalUsage = Math.max(0, surplusDrop);
            }
        }

        boolean estimated = endIndex - startIndex > 1;
        for (int index = startIndex + 1; index <= endIndex; index++) {
            HistoryPoint previous = readings.get(index - 1);
            HistoryPoint current = readings.get(index);
            double intervalFraction = durationMillis <= 0 ? 0
                    : (current.timestamp - previous.timestamp) / (double) durationMillis;
            double elapsedFraction = durationMillis <= 0 ? 0
                    : (current.timestamp - start.timestamp) / (double) durationMillis;
            RechargeSummary intervalRecharge = rechargeSummary(
                    recharges, previous.timestamp, current.timestamp
            );
            RechargeSummary cumulativeRecharge = rechargeSummary(
                    recharges, start.timestamp, current.timestamp
            );

            double displayedAmount = current.amount;
            double displayedSurplus = current.surplus;
            if (valid) {
                displayedAmount = start.amount + cumulativeRecharge.amount
                        - totalCost * elapsedFraction;
                double cumulativeRechargeKwh = totalRecharge.amount > 0 && unitPrice > 0
                        ? cumulativeRecharge.amount / unitPrice : 0;
                displayedSurplus = start.surplus + cumulativeRechargeKwh
                        - totalUsage * elapsedFraction;
            }
            double intervalHours =
                    (current.timestamp - previous.timestamp) / 3_600_000.0;
            double intervalUsage = valid ? totalUsage * intervalFraction : 0;
            double intervalCost = valid ? totalCost * intervalFraction : 0;
            result.add(new BalanceTrendPoint(
                    current,
                    displayedSurplus,
                    displayedAmount,
                    previous.timestamp,
                    intervalUsage,
                    intervalCost,
                    valid && intervalHours > 0 ? intervalUsage / intervalHours : 0,
                    valid && intervalHours > 0 ? intervalCost / intervalHours : 0,
                    intervalRecharge.amount,
                    intervalRecharge.count,
                    valid,
                    unmatchedIncrease,
                    estimated,
                    false,
                    totalHours
            ));
        }
    }

    /**
     * 最后一次变化之后的相同余额尚未被数据源结算。保留接口原值画虚线，并把速率标成未知；
     * 等后续某小时出现新值后，这些点会自动进入 confirmed block 并回填平均速率。
     */
    private static void appendAwaitingTail(
            List<HistoryPoint> readings,
            List<RechargeRecord> recharges,
            int blockStart,
            List<BalanceTrendPoint> result
    ) {
        for (int index = blockStart + 1; index < readings.size(); index++) {
            HistoryPoint previous = readings.get(index - 1);
            HistoryPoint current = readings.get(index);
            RechargeSummary intervalRecharge = rechargeSummary(
                    recharges, previous.timestamp, current.timestamp
            );
            result.add(new BalanceTrendPoint(
                    current,
                    current.surplus,
                    current.amount,
                    previous.timestamp,
                    0, 0, 0, 0,
                    intervalRecharge.amount,
                    intervalRecharge.count,
                    false,
                    false,
                    false,
                    true,
                    (current.timestamp - readings.get(blockStart).timestamp) / 3_600_000.0
            ));
        }
    }

    private static boolean hasMeaningfulChange(HistoryPoint previous, HistoryPoint current) {
        return Math.abs(previous.surplus - current.surplus) >= CHANGE_TOLERANCE
                || Math.abs(previous.amount - current.amount) >= CHANGE_TOLERANCE;
    }

    private static RechargeSummary rechargeSummary(
            List<RechargeRecord> records, long startExclusive, long endInclusive
    ) {
        double amount = 0;
        int count = 0;
        for (RechargeRecord record : records) {
            if (record.timestamp > startExclusive && record.timestamp <= endInclusive) {
                amount += record.amount;
                count++;
            }
        }
        return new RechargeSummary(amount, count);
    }

    /** 使用确认窗口两端的 amount/surplus 比例平均，降低接口两位小数舍入波动。 */
    private static double inferredUnitPrice(HistoryPoint previous, HistoryPoint current) {
        double total = 0;
        int count = 0;
        if (previous.surplus > 0.001 && previous.amount > 0.001) {
            total += previous.amount / previous.surplus;
            count++;
        }
        if (current.surplus > 0.001 && current.amount > 0.001) {
            total += current.amount / current.surplus;
            count++;
        }
        return count == 0 ? 0 : total / count;
    }

    private static final class RechargeSummary {
        private final double amount;
        private final int count;

        private RechargeSummary(double amount, int count) {
            this.amount = amount;
            this.count = count;
        }
    }
}
