package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.mom.mes.dto.WorkOrderStatisticsDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.TimeRangeEnum;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure service regression tests: only the database list boundary is substituted.
 */
public class MesStatisticsServiceImplTest {
    private static final LocalDateTime START = LocalDateTime.of(2020, 1, 2, 0, 0);
    private static final LocalDateTime END = START.plusDays(1).minusNanos(1);
    private final List<MesTaskEntity> rows = new ArrayList<>();
    private String query;

    // Also runnable without upgrading the repository's legacy Surefire 2.12.4 (no Jupiter provider).
    public static void main(String[] args) throws Exception {
        int passed = 0, failed = 0;
        for (var method : MesStatisticsServiceImplTest.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Test.class)) {
                continue;
            }
            try {
                method.invoke(new MesStatisticsServiceImplTest());
                System.out.println("PASS " + method.getName());
                passed++;
            } catch (java.lang.reflect.InvocationTargetException ex) {
                System.out.println("FAIL " + method.getName() + ": " + ex.getCause());
                failed++;
            }
        }
        System.out.println("Tests: " + (passed + failed) + ", passed: " + passed + ", failed: " + failed);
        if (failed > 0) {
            throw new AssertionError("MES statistics regressions failed: " + failed);
        }
    }

    private MesTaskEntity row(String status, LocalDateTime due, LocalDateTime finish) {
        MesTaskEntity task = new MesTaskEntity();
        task.setStatus(status);
        task.setPlanEndDate(due);
        task.setFinishTime(finish);
        task.setCreateTime(START.minusMonths(1));
        rows.add(task);
        return task;
    }

    @SuppressWarnings("unchecked")
    private List<WorkOrderStatisticsDTO> run(LocalDateTime start, LocalDateTime end) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "statistics-test"),
                MesTaskEntity.class);
        MesTaskService tasks = (MesTaskService) Proxy.newProxyInstance(MesTaskService.class.getClassLoader(),
                new Class<?>[]{MesTaskService.class}, (p, m, a) -> {
                    if (m.getName().equals("list")) {
                        var wrapper = (LambdaQueryWrapper<MesTaskEntity>) a[0];
                        query = wrapper.getSqlSegment();
                        // Simulate the actual selected date column, including inclusive boundaries.
                        boolean due = query.contains("plan_end_date");
                        return rows.stream().filter(t -> {
                            LocalDateTime date = due ? t.getPlanEndDate() : t.getCreateTime();
                            return date != null && !date.isBefore(start) && !date.isAfter(end);
                        }).toList();
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        return new MesStatisticsServiceImpl(tasks, null).getWorkOrderStatistics(TimeRangeEnum.CUSTOM, start, end);
    }

    @Test
    void completionUsesOfficialStatesAndActualFinishVersusDueTime() {
        row("FINISH", START.plusHours(12), START.plusHours(11));
        row("FINISH", START.plusHours(12), START.plusHours(13));
        row("PRODUCT", START.plusHours(12), null);
        var day = run(START, END).get(0);

        assertEquals(3L, day.getTotalCount());
        assertEquals(2L, day.getCompletedCount());
        assertEquals(1L, day.getInProgressCount());
        assertEquals(1L, day.getOnTimeCompletedCount());
        assertEquals(1L, day.getDelayedCompletedCount());
        assertEquals(100.0 / 3, day.getOnTimeCompletionRate(), 0.00001);
        assertTrue(query.contains("plan_end_date"));
        assertFalse(query.contains("create_time"));
    }

    @Test
    void dueBoundariesAreInclusiveAndMissingDueDateDoesNotFallBackToCreation() {
        row("CREATE", START, null);
        row("ISSUE", END, null);
        row("DISPATCH", START.minusNanos(1), null);
        row("PRODUCT", END.plusNanos(1), null);
        row("FINISH", null, START).setCreateTime(START);
        var day = run(START, END).get(0);

        assertEquals(2L, day.getTotalCount());
        assertEquals(2L, day.getNotStartedCount());
    }

    @Test
    void closingWithoutFinishEvidenceDoesNotManufactureCompletion() {
        row("CLOSE", START.plusHours(1), null);
        row("CLOSE", START.plusHours(1), START.plusHours(1));
        row("ALIEN", START.plusHours(1), null);
        var day = run(START, END).get(0);

        assertEquals(1L, day.getCompletedCount());
        assertEquals(1L, day.getOnTimeCompletedCount());
        assertEquals(0L, day.getCancelledCount());
        assertEquals(0L, day.getPausedCount());
        assertEquals(100.0, day.getCompletionRate());
    }

    @Test
    void completedWithoutFinishTimeRemainsUnknownForTimeliness() {
        row("FINISH", START.plusHours(1), null);
        var day = run(START, END).get(0);

        assertEquals(1L, day.getCompletedCount());
        assertEquals(0L, day.getOnTimeCompletedCount());
        assertEquals(0L, day.getDelayedCompletedCount());
        assertEquals(0.0, day.getOnTimeCompletionRate());
    }

    @Test
    void dateRangeReturnsEmptyDaysAndRejectsReversedBounds() {
        assertEquals(2, run(START, END.plusDays(1)).size());
        assertEquals(0L, run(START, END).get(0).getTotalCount());
        assertThrows(IllegalArgumentException.class, () -> run(END, START));
    }

    @Test
    void unfinishedOverdueAndFutureDueAreDistinguished() {
        LocalDateTime now = LocalDateTime.now();
        row("PRODUCT", now.minusHours(1), null);
        row("PRODUCT", now.plusHours(1), null);
        var days = run(now.minusHours(2), now.plusHours(2));

        assertEquals(1L, days.stream().mapToLong(WorkOrderStatisticsDTO::getOverdueUnfinishedCount).sum());
        assertEquals(2L, days.stream().mapToLong(WorkOrderStatisticsDTO::getInProgressCount).sum());
    }

    private MesProduceTaskEntity production(String code, String good, String ng) {
        var t = new MesProduceTaskEntity();
        t.setRouteCode(code);
        t.setRouteName("同名工序");
        t.setCompleteNum(good == null ? null : new BigDecimal(good));
        t.setUnPassNum(ng == null ? null : new BigDecimal(ng));
        return t;
    }

    private MesStatisticsServiceImpl qualityService(List<MesProduceTaskEntity> data) {
        MesProduceTaskService source = (MesProduceTaskService) Proxy.newProxyInstance(
                MesProduceTaskService.class.getClassLoader(), new Class<?>[]{MesProduceTaskService.class},
                (p, m, a) -> {
                    if (m.getName().equals("list")) {
                        return data;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        return new MesStatisticsServiceImpl(null, source);
    }

    @Test
    void qualityTotalAddsGoodAndNgWithoutSubtractingNgTwice() {
        var result = qualityService(List.of(production("P1", "8", "2")))
                .getProcessDefectRateRanking(TimeRangeEnum.CUSTOM, START, END, 10).get(0);

        assertEquals(0, new BigDecimal("20").compareTo(result.getDefectRate()));
        assertEquals("P1", result.getProcessCode());
        assertNull(result.getMainDefectReason());
        assertNull(result.getChangePercentage());
    }

    @Test
    void qualityPreservesFractionalQuantitiesAndSeparatesDifferentCodes() {
        var result = qualityService(List.of(production("P1", "1.5", "0.5"), production("P2", "8", "2")))
                .getProcessDefectRateRanking(TimeRangeEnum.CUSTOM, START, END, 10);

        assertEquals(2, result.size());
        assertEquals(0, new BigDecimal("25").compareTo(result.get(0).getDefectRate()));
    }

    @Test
    void missingAndEmptyQualityEvidenceCannotClaimOneHundredPercentQualified() {
        for (var sample : List.of(production("P1", null, "2"), production("P1", "0", "0"))) {
            var result = qualityService(List.of(sample))
                    .getProcessDefectRateRanking(TimeRangeEnum.CUSTOM, START, END, 10).get(0);
            assertNull(result.getDefectRate());
            assertNull(result.getQualifiedRate());
        }
    }

    @Test
    void badQuantityPreservesFractionsAndHasNoFabricatedComparisons() {
        var result = qualityService(List.of(production("P1", "1", "0.5")))
                .getBadNum(TimeRangeEnum.CUSTOM, START, END);

        assertEquals("0.5", result.get("currentMonthSum"));
        assertEquals("--", result.get("momComparison"));
        assertEquals("unknown", result.get("momFlag"));
    }

    @Test
    void inProgressCardUsesExecutionStatusRatherThanMissingGoodQuantity() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "wip-test"),
                MesProduceTaskEntity.class);

        MesProduceTaskService source = (MesProduceTaskService) Proxy.newProxyInstance(
                MesProduceTaskService.class.getClassLoader(), new Class<?>[]{MesProduceTaskService.class},
                (p, m, a) -> {
                    var wrapper = (LambdaQueryWrapper<MesProduceTaskEntity>) a[0];
                    assertFalse(wrapper.getSqlSegment().contains("complete_num IS NULL"));
                    assertTrue(wrapper.getParamNameValuePairs().containsValue("PRODUCT"));
                    return List.of(production("P1", "0", "0"));
                });
        assertEquals("1", new MesStatisticsServiceImpl(null, source).getWipNum(TimeRangeEnum.CUSTOM, START, END)
                .get("currentMonthSum"));
    }

    @Test
    void goodQuantityCardPreservesFractionsAndMissingEvidence() {
        assertEquals("1.5", qualityService(List.of(production("P1", "1.5", "0")))
                .getProductNum(TimeRangeEnum.CUSTOM, START, END).get("currentMonthSum"));
        assertEquals("--", qualityService(List.of(production("P1", null, "0")))
                .getProductNum(TimeRangeEnum.CUSTOM, START, END).get("currentMonthSum"));
    }
}
