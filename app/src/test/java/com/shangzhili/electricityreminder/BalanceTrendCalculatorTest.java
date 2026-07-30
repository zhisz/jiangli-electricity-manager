package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZoneId;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Calendar;
import java.util.TimeZone;

/** 验证小时速率计算和“一天多笔充值”不会在后续迭代中退化。 */
public final class BalanceTrendCalculatorTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    public void multipleRechargesInOneIntervalAreAccumulated() {
        long start = 1_800_000_000_000L;
        HistoryPoint previous = new HistoryPoint(start, 100, 100);
        // 一小时内充值 30 + 20 元、实际消耗 10 元后，余额应为 140 元。
        HistoryPoint current = new HistoryPoint(start + 3_600_000L, 140, 140);
        List<RechargeRecord> recharges = Arrays.asList(
                new RechargeRecord(1, start + 15 * 60_000L, 30, ZONE),
                new RechargeRecord(2, start + 45 * 60_000L, 20, ZONE)
        );

        BalanceTrendPoint interval = BalanceTrendCalculator.calculate(
                Arrays.asList(previous, current), recharges
        ).get(1);

        assertTrue(interval.rateValid);
        assertEquals(2, interval.rechargeCount);
        assertEquals(50, interval.rechargeAmount, 0.0001);
        assertEquals(10, interval.usageKwh, 0.0001);
        assertEquals(10, interval.rateKwhPerHour, 0.0001);
    }

    @Test
    public void unregisteredIncreaseIsMarkedInvalidInsteadOfZeroUsage() {
        long start = 1_800_000_000_000L;
        HistoryPoint previous = new HistoryPoint(start, 100, 60);
        HistoryPoint current = new HistoryPoint(start + 3_600_000L, 120, 72);

        BalanceTrendPoint interval = BalanceTrendCalculator.calculate(
                Arrays.asList(previous, current), Collections.emptyList()
        ).get(1);

        assertFalse(interval.rateValid);
        assertTrue(interval.unmatchedIncrease);
    }

    @Test
    public void rechargeEventsAreAssignedToTheirExactHourlyIntervals() {
        long start = 1_800_000_000_000L;
        List<HistoryPoint> readings = Arrays.asList(
                new HistoryPoint(start, 100, 100),
                new HistoryPoint(start + 3_600_000L, 125, 125),
                new HistoryPoint(start + 7_200_000L, 143, 143)
        );
        List<RechargeRecord> recharges = Arrays.asList(
                new RechargeRecord(1, start + 1_800_000L, 30, ZONE),
                new RechargeRecord(2, start + 5_400_000L, 20, ZONE)
        );

        List<BalanceTrendPoint> result =
                BalanceTrendCalculator.calculate(readings, recharges);

        assertEquals(1, result.get(1).rechargeCount);
        assertEquals(1, result.get(2).rechargeCount);
        assertEquals(5, result.get(1).usageKwh, 0.0001);
        assertEquals(2, result.get(2).usageKwh, 0.0001);
    }

    @Test
    public void monthlyStatisticsAlsoSumMultipleSameDayRechargesByTimestamp() {
        LocalDate day = LocalDate.of(2026, 7, 1);
        long start = day.atTime(8, 0).atZone(ZONE).toInstant().toEpochMilli();
        long end = day.plusDays(1).atTime(8, 0).atZone(ZONE).toInstant().toEpochMilli();
        List<HistoryPoint> readings = Arrays.asList(
                new HistoryPoint(start, 100, 100),
                new HistoryPoint(end, 140, 140)
        );
        List<RechargeRecord> recharges = Arrays.asList(
                new RechargeRecord(1, day.atTime(12, 0).atZone(ZONE)
                        .toInstant().toEpochMilli(), 30, ZONE),
                new RechargeRecord(2, day.atTime(18, 0).atZone(ZONE)
                        .toInstant().toEpochMilli(), 20, ZONE)
        );

        UsagePeriodStats stats = UsageStatisticsCalculator.calculate(
                readings, recharges, day, day.plusDays(2), ZONE
        );

        assertTrue(stats.hasData());
        assertEquals(10, stats.usageKwh, 0.0001);
        assertEquals(10, stats.costAmount, 0.0001);
        assertEquals(0, stats.excludedRechargeIntervals);
    }

    @Test
    public void hourlySchedulerAlwaysTargetsNextWholeHourIncludingMidnight() {
        TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
        Calendar now = Calendar.getInstance(zone);
        now.set(2026, Calendar.JULY, 30, 23, 59, 42);
        now.set(Calendar.MILLISECOND, 731);

        long trigger = Scheduler.nextWholeHour(now.getTimeInMillis(), zone);
        Calendar next = Calendar.getInstance(zone);
        next.setTimeInMillis(trigger);

        assertEquals(2026, next.get(Calendar.YEAR));
        assertEquals(Calendar.JULY, next.get(Calendar.MONTH));
        assertEquals(31, next.get(Calendar.DAY_OF_MONTH));
        assertEquals(0, next.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, next.get(Calendar.MINUTE));
        assertEquals(0, next.get(Calendar.SECOND));
        assertEquals(0, next.get(Calendar.MILLISECOND));
    }

    @Test
    public void delayedBalanceChangeIsSpreadAcrossTheWholeConfirmedWindow() {
        long start = 1_800_000_000_000L;
        List<HistoryPoint> readings = Arrays.asList(
                new HistoryPoint(start, 100, 100),
                new HistoryPoint(start + 3_600_000L, 100, 100),
                new HistoryPoint(start + 7_200_000L, 100, 100),
                new HistoryPoint(start + 10_800_000L, 94, 94)
        );

        List<BalanceTrendPoint> result =
                BalanceTrendCalculator.calculate(readings, Collections.emptyList());

        for (int index = 1; index <= 3; index++) {
            assertTrue(result.get(index).rateValid);
            assertTrue(result.get(index).estimated);
            assertFalse(result.get(index).awaitingSourceUpdate);
            assertEquals(2, result.get(index).usageKwh, 0.0001);
            assertEquals(2, result.get(index).rateKwhPerHour, 0.0001);
            assertEquals(3, result.get(index).estimateSpanHours, 0.0001);
        }
        assertEquals(98, result.get(1).displayedSurplus, 0.0001);
        assertEquals(96, result.get(2).displayedSurplus, 0.0001);
        assertEquals(94, result.get(3).displayedSurplus, 0.0001);
    }

    @Test
    public void unchangedTrailingBalanceIsPendingRatherThanFakeZeroUsage() {
        long start = 1_800_000_000_000L;
        List<HistoryPoint> readings = Arrays.asList(
                new HistoryPoint(start, 100, 100),
                new HistoryPoint(start + 3_600_000L, 100, 100),
                new HistoryPoint(start + 7_200_000L, 100, 100)
        );

        List<BalanceTrendPoint> result =
                BalanceTrendCalculator.calculate(readings, Collections.emptyList());

        assertFalse(result.get(1).rateValid);
        assertFalse(result.get(2).rateValid);
        assertTrue(result.get(1).awaitingSourceUpdate);
        assertTrue(result.get(2).awaitingSourceUpdate);
        assertEquals(100, result.get(2).displayedSurplus, 0.0001);
    }

    @Test
    public void dailyUsageAlsoSpreadsDelayedChangesAcrossConfirmedDays() {
        LocalDate day = LocalDate.of(2026, 7, 1);
        List<HistoryPoint> readings = Arrays.asList(
                pointAt(day, 100),
                pointAt(day.plusDays(1), 100),
                pointAt(day.plusDays(2), 94)
        );

        UsagePeriodStats firstDay = UsageStatisticsCalculator.calculate(
                readings, Collections.emptyList(), day, day.plusDays(1), ZONE
        );
        UsagePeriodStats secondDay = UsageStatisticsCalculator.calculate(
                readings, Collections.emptyList(), day.plusDays(1), day.plusDays(2), ZONE
        );

        assertEquals(3, firstDay.usageKwh, 0.0001);
        assertEquals(3, secondDay.usageKwh, 0.0001);
        assertEquals(1, firstDay.coveredDays);
        assertEquals(1, secondDay.coveredDays);
    }

    @Test
    public void dailyTrailingPlateauDoesNotPretendToBeConfirmedZero() {
        LocalDate day = LocalDate.of(2026, 7, 1);
        List<HistoryPoint> readings = Arrays.asList(
                pointAt(day, 100),
                pointAt(day.plusDays(1), 100)
        );

        UsagePeriodStats stats = UsageStatisticsCalculator.calculate(
                readings, Collections.emptyList(), day, day.plusDays(2), ZONE
        );

        assertFalse(stats.hasData());
    }

    private static HistoryPoint pointAt(LocalDate date, double value) {
        long timestamp = date.atTime(23, 0).atZone(ZONE).toInstant().toEpochMilli();
        return new HistoryPoint(timestamp, value, value);
    }
}
