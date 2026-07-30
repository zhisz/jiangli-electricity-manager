package com.shangzhili.electricityreminder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 根据按日期排列的余额采样估算一个日期区间内的用电量和电费。
 *
 * <p>相邻两次读数下降时，下降量视为这段时间的估算消耗。若用户在该精确时间区间登记了充值，
 * 先用“上次金额 + 充值金额 - 本次金额”还原期间消费，再通过余额中的元/度比例换算电量。
 * 没有匹配充值记录的净上涨仍会被排除，而不是错误记为 0。
 * 如果数据源连续多天返回同一余额，会等待下一次变化，把累计消耗按整个确认窗口的天数
 * 均摊，避免出现“前几天为 0、更新当天突然暴增”。窗口末端尚未变化的读数不作为零用电。</p>
 */
public final class UsageStatisticsCalculator {
    private static final double CHANGE_TOLERANCE = 0.005;

    private UsageStatisticsCalculator() {}

    public static UsagePeriodStats calculate(
            List<HistoryPoint> points,
            List<RechargeRecord> recharges,
            LocalDate startInclusive,
            LocalDate endExclusive,
            ZoneId zoneId
    ) {
        double usage = 0;
        double cost = 0;
        long coveredDays = 0;
        int excludedRechargeIntervals = 0;

        int blockStart = 0;
        while (blockStart < points.size() - 1) {
            int blockEnd = nextChangedPoint(points, blockStart);
            // 最后连续相同的余额尚未等到数据源结算，不能把它们作为真实零用电计入统计。
            if (blockEnd < 0) break;
            HistoryPoint previous = points.get(blockStart);
            HistoryPoint current = points.get(blockEnd);
            LocalDate intervalStart = toDate(previous.timestamp, zoneId);
            LocalDate intervalEnd = toDate(current.timestamp, zoneId);
            long intervalDays = ChronoUnit.DAYS.between(intervalStart, intervalEnd);
            if (intervalDays <= 0) {
                blockStart = blockEnd;
                continue;
            }

            LocalDate overlapStart = later(intervalStart, startInclusive);
            LocalDate overlapEnd = earlier(intervalEnd, endExclusive);
            long overlapDays = ChronoUnit.DAYS.between(overlapStart, overlapEnd);
            if (overlapDays <= 0) {
                blockStart = blockEnd;
                continue;
            }

            double rechargeAmount = rechargeAmountForInterval(
                    recharges, previous.timestamp, current.timestamp
            );
            double surplusDrop = previous.surplus - current.surplus;
            double amountDrop = previous.amount + rechargeAmount - current.amount;

            // 充值记录金额仍不足以解释余额上涨时，说明存在漏记充值或数据异常，继续排除。
            if ((rechargeAmount <= 0 && surplusDrop < -0.01)
                    || (rechargeAmount > 0 && amountDrop < -0.01)) {
                excludedRechargeIntervals++;
                blockStart = blockEnd;
                continue;
            }

            double intervalUsage;
            if (rechargeAmount > 0) {
                double unitPrice = inferredUnitPrice(previous, current);
                if (unitPrice <= 0) {
                    // 理论上接口的 amount/surplus 会提供有效单价；异常时不伪造电量结果。
                    excludedRechargeIntervals++;
                    blockStart = blockEnd;
                    continue;
                }
                intervalUsage = Math.max(0, amountDrop) / unitPrice;
            } else {
                intervalUsage = Math.max(0, surplusDrop);
            }

            // 同一耗电区间按时间比例计入目标月份，避免跨月采样全部堆到某一个月。
            double fraction = (double) overlapDays / intervalDays;
            usage += intervalUsage * fraction;
            cost += Math.max(0, amountDrop) * fraction;
            coveredDays += overlapDays;
            blockStart = blockEnd;
        }
        return new UsagePeriodStats(usage, cost, coveredDays, excludedRechargeIntervals);
    }

    /** 从上一个已确认变化点向后寻找下一次原始余额或金额变化。 */
    private static int nextChangedPoint(List<HistoryPoint> points, int startIndex) {
        for (int index = startIndex + 1; index < points.size(); index++) {
            HistoryPoint previous = points.get(index - 1);
            HistoryPoint current = points.get(index);
            if (Math.abs(previous.surplus - current.surplus) >= CHANGE_TOLERANCE
                    || Math.abs(previous.amount - current.amount) >= CHANGE_TOLERANCE) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 充值归入 (上次采样时刻, 本次采样时刻]。同一天多笔会逐笔相加，但不会错误落入
     * 当天的其他小时；这是 0.16.0 精确充值时间能够修正时段速率的关键。
     */
    private static double rechargeAmountForInterval(
            List<RechargeRecord> records,
            long intervalStart,
            long intervalEnd
    ) {
        double result = 0;
        for (RechargeRecord record : records) {
            if (record.timestamp > intervalStart && record.timestamp <= intervalEnd) {
                result += record.amount;
            }
        }
        return result;
    }

    /** 使用区间两端有效的 amount/surplus 比例平均，降低两位小数舍入带来的波动。 */
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

    private static LocalDate toDate(long timestamp, ZoneId zoneId) {
        return Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate();
    }

    private static LocalDate later(LocalDate first, LocalDate second) {
        return first.isAfter(second) ? first : second;
    }

    private static LocalDate earlier(LocalDate first, LocalDate second) {
        return first.isBefore(second) ? first : second;
    }
}
