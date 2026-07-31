package com.shangzhili.electricityreminder;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成“平台余额事实层 + 延迟更新估算层”。
 *
 * <p>平台采样永远保存在 {@link BalanceTrendPoint#reading} 中，绘图时使用阶梯线原样展示。
 * 估算层只回答“已经确认的累计变化较可能分布在哪些小时”，绝不把插值值写回数据库。
 * 一旦平台给出下一次变化，同一确认窗口会重新计算，且所有小时用量之和严格等于窗口
 * 两端余额差（加上期间官方确认的充值）。</p>
 */
public final class BalanceTrendCalculator {
    private static final double CHANGE_TOLERANCE = 0.005;
    private static final double INCREASE_TOLERANCE = 0.01;
    private static final long HOUR_MILLIS = 3_600_000L;
    private static final long SEVEN_DAYS_MILLIS = 7L * 24 * HOUR_MILLIS;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

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
        RateHistory history = new RateHistory();

        HistoryPoint first = readings.get(0);
        result.add(new BalanceTrendPoint(
                first, first.surplus, first.amount, first.timestamp,
                0, 0, 0, 0, 0, 0,
                false, false, false, false, 0
        ));

        int blockStart = 0;
        for (int index = 1; index < readings.size(); index++) {
            if (!hasMeaningfulChange(readings.get(index - 1), readings.get(index))) continue;
            appendConfirmedBlock(
                    readings, safeRecharges, blockStart, index, result, history
            );
            blockStart = index;
        }
        appendAwaitingTail(readings, safeRecharges, blockStart, result, history);
        return result;
    }

    /**
     * 将已确认的累计消耗按“过去 7 天相同时段速率”分配。历史样本不够时才均匀分配；
     * 最后一个区间吸收浮点误差，因此分配总和不会慢慢偏离平台真实累计差。
     */
    private static void appendConfirmedBlock(
            List<HistoryPoint> readings,
            List<RechargeRecord> recharges,
            int startIndex,
            int endIndex,
            List<BalanceTrendPoint> result,
            RateHistory history
    ) {
        HistoryPoint start = readings.get(startIndex);
        HistoryPoint end = readings.get(endIndex);
        long durationMillis = end.timestamp - start.timestamp;
        double totalHours = durationMillis / (double) HOUR_MILLIS;
        RechargeSummary totalRecharge = rechargeSummary(
                recharges, start.timestamp, end.timestamp
        );
        double amountDrop = start.amount + totalRecharge.amount - end.amount;
        double unitPrice = inferredUnitPrice(start, end);
        double rechargeKwh = unitPrice > 0 ? totalRecharge.amount / unitPrice : 0;
        double surplusDrop = start.surplus + rechargeKwh - end.surplus;
        boolean unmatchedIncrease = surplusDrop < -INCREASE_TOLERANCE
                || amountDrop < -INCREASE_TOLERANCE;
        boolean valid = totalHours > 0.01 && !unmatchedIncrease
                && (totalRecharge.amount <= 0 || unitPrice > 0);
        double totalUsage = valid ? Math.max(0, surplusDrop) : 0;
        double totalCost = valid ? Math.max(0, amountDrop) : 0;

        int intervalCount = endIndex - startIndex;
        double[] rawWeights = new double[intervalCount];
        boolean enoughHistory = history.sizeSince(start.timestamp - SEVEN_DAYS_MILLIS) >= 3;
        double weightSum = 0;
        for (int offset = 0; offset < intervalCount; offset++) {
            HistoryPoint previous = readings.get(startIndex + offset);
            HistoryPoint current = readings.get(startIndex + offset + 1);
            double hours = Math.max(0, (current.timestamp - previous.timestamp)
                    / (double) HOUR_MILLIS);
            double rate = enoughHistory
                    ? history.meanForHour(hourOf(previous.timestamp),
                    start.timestamp - SEVEN_DAYS_MILLIS)
                    : Double.NaN;
            if (!Double.isFinite(rate)) rate = history.meanSince(
                    start.timestamp - SEVEN_DAYS_MILLIS
            );
            rawWeights[offset] = Double.isFinite(rate) && rate > 0 ? rate * hours : hours;
            weightSum += rawWeights[offset];
        }
        if (weightSum <= 0) weightSum = intervalCount;

        double usedSoFar = 0;
        double costSoFar = 0;
        double cumulativeOfficialAmount = 0;
        for (int offset = 0; offset < intervalCount; offset++) {
            int index = startIndex + offset + 1;
            HistoryPoint previous = readings.get(index - 1);
            HistoryPoint current = readings.get(index);
            RechargeSummary intervalRecharge = rechargeSummary(
                    recharges, previous.timestamp, current.timestamp
            );
            cumulativeOfficialAmount += intervalRecharge.amount;
            double fraction = rawWeights[offset] / weightSum;
            double intervalUsage = offset == intervalCount - 1
                    ? totalUsage - usedSoFar : totalUsage * fraction;
            double intervalCost = offset == intervalCount - 1
                    ? totalCost - costSoFar : totalCost * fraction;
            usedSoFar += intervalUsage;
            costSoFar += intervalCost;
            double intervalHours = (current.timestamp - previous.timestamp)
                    / (double) HOUR_MILLIS;
            double displayedAmount = valid
                    ? start.amount + cumulativeOfficialAmount - costSoFar : current.amount;
            double displayedSurplus = valid
                    ? start.surplus + (unitPrice > 0
                    ? cumulativeOfficialAmount / unitPrice : 0) - usedSoFar
                    : current.surplus;
            result.add(new BalanceTrendPoint(
                    current, displayedSurplus, displayedAmount, previous.timestamp,
                    valid ? intervalUsage : 0, valid ? intervalCost : 0,
                    valid && intervalHours > 0 ? intervalUsage / intervalHours : 0,
                    valid && intervalHours > 0 ? intervalCost / intervalHours : 0,
                    intervalRecharge.amount, intervalRecharge.count,
                    valid, unmatchedIncrease, valid && intervalCount > 1,
                    false, totalHours
            ));
        }

        // 只有确认窗口才可反过来训练未来预测；未确认上涨绝不能污染历史小时模型。
        if (valid) {
            for (int offset = 0; offset < intervalCount; offset++) {
                BalanceTrendPoint point = result.get(result.size() - intervalCount + offset);
                history.add(point.reading.timestamp, hourOf(point.intervalStart),
                        point.rateKwhPerHour);
            }
        }
    }

    /**
     * 平台尚未给出下一次变化时，使用最近 7 天同小时速率预测尾部消耗。阴影范围使用
     * 历史均值 ± 1.28 个标准差（约 80% 经验区间）；样本不足时不伪造预测线，只保留
     * “等待平台更新”，直至积累出至少一个可用的已确认速率。
     */
    private static void appendAwaitingTail(
            List<HistoryPoint> readings,
            List<RechargeRecord> recharges,
            int blockStart,
            List<BalanceTrendPoint> result,
            RateHistory history
    ) {
        HistoryPoint start = readings.get(blockStart);
        double displayedSurplus = start.surplus;
        double displayedAmount = start.amount;
        double lowConsumption = 0;
        double highConsumption = 0;
        double unitPrice = inferredUnitPrice(start, start);
        for (int index = blockStart + 1; index < readings.size(); index++) {
            HistoryPoint previous = readings.get(index - 1);
            HistoryPoint current = readings.get(index);
            double hours = Math.max(0, (current.timestamp - previous.timestamp)
                    / (double) HOUR_MILLIS);
            long since = current.timestamp - SEVEN_DAYS_MILLIS;
            RateStats stats = history.statsForHour(hourOf(previous.timestamp), since);
            if (stats.count == 0) stats = history.statsSince(since);
            boolean forecastAvailable = stats.count > 0 && stats.mean >= 0;
            double predictedUsage = forecastAvailable ? stats.mean * hours : 0;
            double deviation = stats.count >= 2 ? 1.28 * stats.standardDeviation()
                    : stats.mean * 0.5;
            double intervalLow = forecastAvailable
                    ? Math.max(0, stats.mean - deviation) * hours : 0;
            double intervalHigh = forecastAvailable
                    ? Math.max(intervalLow, stats.mean + deviation) * hours : 0;
            RechargeSummary intervalRecharge = rechargeSummary(
                    recharges, previous.timestamp, current.timestamp
            );
            double rechargeKwh = unitPrice > 0 ? intervalRecharge.amount / unitPrice : 0;
            displayedSurplus += rechargeKwh - predictedUsage;
            displayedAmount += intervalRecharge.amount - predictedUsage * unitPrice;
            lowConsumption += intervalLow;
            highConsumption += intervalHigh;
            double cumulativeRecharge = rechargeSummary(
                    recharges, start.timestamp, current.timestamp
            ).amount;
            double baseWithRecharge = start.surplus
                    + (unitPrice > 0 ? cumulativeRecharge / unitPrice : 0);
            double highBalance = baseWithRecharge - lowConsumption;
            double lowBalance = baseWithRecharge - highConsumption;
            result.add(new BalanceTrendPoint(
                    current, forecastAvailable ? displayedSurplus : current.surplus,
                    forecastAvailable ? displayedAmount : current.amount,
                    previous.timestamp, predictedUsage, predictedUsage * unitPrice,
                    forecastAvailable ? stats.mean : 0,
                    forecastAvailable ? stats.mean * unitPrice : 0,
                    intervalRecharge.amount, intervalRecharge.count,
                    forecastAvailable, false, forecastAvailable, true,
                    (current.timestamp - start.timestamp) / (double) HOUR_MILLIS,
                    forecastAvailable ? lowBalance : current.surplus,
                    forecastAvailable ? highBalance : current.surplus,
                    forecastAvailable ? lowBalance * unitPrice : current.amount,
                    forecastAvailable ? highBalance * unitPrice : current.amount
            ));
        }
    }

    private static boolean hasMeaningfulChange(HistoryPoint previous, HistoryPoint current) {
        return Math.abs(previous.surplus - current.surplus) >= CHANGE_TOLERANCE
                || Math.abs(previous.amount - current.amount) >= CHANGE_TOLERANCE;
    }

    /** 手工充值仍供月度统计使用，但趋势精确校正只接收官方订单确认记录。 */
    private static RechargeSummary rechargeSummary(
            List<RechargeRecord> records, long startExclusive, long endInclusive
    ) {
        double amount = 0;
        int count = 0;
        for (RechargeRecord record : records) {
            if (record.officiallyConfirmed
                    && record.timestamp > startExclusive && record.timestamp <= endInclusive) {
                amount += record.amount;
                count++;
            }
        }
        return new RechargeSummary(amount, count);
    }

    private static int hourOf(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(SHANGHAI).getHour();
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

    private static final class RateSample {
        private final long timestamp;
        private final int hour;
        private final double rate;

        private RateSample(long timestamp, int hour, double rate) {
            this.timestamp = timestamp;
            this.hour = hour;
            this.rate = rate;
        }
    }

    private static final class RateStats {
        private int count;
        private double sum;
        private double squaredSum;
        private double mean;

        private void add(double value) {
            count++;
            sum += value;
            squaredSum += value * value;
            mean = sum / count;
        }

        private double standardDeviation() {
            if (count < 2) return 0;
            return Math.sqrt(Math.max(0, squaredSum / count - mean * mean));
        }
    }

    private static final class RateHistory {
        private final List<RateSample> samples = new ArrayList<>();

        private void add(long timestamp, int hour, double rate) {
            if (Double.isFinite(rate) && rate >= 0) {
                samples.add(new RateSample(timestamp, hour, rate));
            }
        }

        private int sizeSince(long since) {
            int count = 0;
            for (RateSample sample : samples) if (sample.timestamp >= since) count++;
            return count;
        }

        private double meanForHour(int hour, long since) {
            RateStats stats = statsForHour(hour, since);
            return stats.count == 0 ? Double.NaN : stats.mean;
        }

        private double meanSince(long since) {
            RateStats stats = statsSince(since);
            return stats.count == 0 ? Double.NaN : stats.mean;
        }

        private RateStats statsForHour(int hour, long since) {
            RateStats stats = new RateStats();
            for (RateSample sample : samples) {
                if (sample.timestamp >= since && sample.hour == hour) stats.add(sample.rate);
            }
            return stats;
        }

        private RateStats statsSince(long since) {
            RateStats stats = new RateStats();
            for (RateSample sample : samples) {
                if (sample.timestamp >= since) stats.add(sample.rate);
            }
            return stats;
        }
    }
}
