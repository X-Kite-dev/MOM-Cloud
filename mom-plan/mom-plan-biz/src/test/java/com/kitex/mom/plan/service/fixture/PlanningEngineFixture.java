package com.kitex.mom.plan.service.fixture;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.feign.*;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.*;
import com.kitex.mom.plan.service.conflict.ConflictEngine;
import com.kitex.mom.plan.service.impl.*;
import com.kitex.mom.plan.service.selector.RuleSetSelector;
import com.kitex.mom.plan.service.solver.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 排产编排单元夹具：持久化和远端端口替身；排序证明由真实Registry/Greedy求解器生成。
 */
public abstract class PlanningEngineFixture {

    protected final PlnSchedulingTaskMapper plnSchedulingTaskMapper = mock(PlnSchedulingTaskMapper.class);

    protected final PlnIssueMapper plnIssueMapper = mock(PlnIssueMapper.class);

    protected final PlnSchedulingTaskDetailMapper plnSchedulingTaskDetailMapper = mock(PlnSchedulingTaskDetailMapper.class);

    protected final PlnSchedulingConflictMapper plnSchedulingConflictMapper = mock(PlnSchedulingConflictMapper.class);

    protected final PlnUpdateLogService plnUpdateLogService = mock(PlnUpdateLogService.class);

    protected final RemoteMesTaskService remoteTaskService = mock(RemoteMesTaskService.class);

    protected final PlnMaterialConstraintService materialConstraintService = mock(PlnMaterialConstraintService.class);

    protected final PlnOperationSchedulingService operationSchedulingService = mock(PlnOperationSchedulingService.class);

    protected final PlnSchedulingBatchService schedulingBatchService = mock(PlnSchedulingBatchService.class);

    protected final RuleSetSelector ruleSetSelector = mock(RuleSetSelector.class);

    protected final PlnSchedulingSequenceProofMapper sequenceProofs = mock(PlnSchedulingSequenceProofMapper.class);

    protected final Map<Long, PlnSchedulingTaskEntity> tasks = new LinkedHashMap<>();

    protected final List<PlnSchedulingSequenceProof> proofs = new ArrayList<>();

    protected final List<PlnSchedulingTaskDetailEntity> snapshots = new ArrayList<>();

    protected PlnSchedulingEngineServiceImpl plnSchedulingEngineService;

    @BeforeEach
    void initializePlanningPorts() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SchedulingExecutionContext.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "planning-engine-fixture");
        for (var type : List.of(PlnIssueEntity.class, PlnSchedulingTaskEntity.class,
                PlnSchedulingTaskDetailEntity.class, PlnSchedulingConflictEntity.class,
                PlnSchedulingRuleSetEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
        plnSchedulingEngineService = new PlnSchedulingEngineServiceImpl(
                plnSchedulingTaskMapper, mock(PlnSchedulingOperationMapper.class),
                mock(PlnSchedulingScenarioMapper.class), mock(PlnApsCaseMapper.class),
                mock(PlnObjectiveTemplateService.class), mock(PlnProductDemandMapper.class),
                plnIssueMapper, plnSchedulingConflictMapper, plnSchedulingTaskDetailMapper,
                mock(PlnSchedulingBatchMapper.class), mock(PlnSchedulingBatchAllocationMapper.class),
                plnUpdateLogService, remoteTaskService, mock(PlanMatMrpService.class),
                materialConstraintService, operationSchedulingService, mock(PlnResourceLoadService.class),
                schedulingBatchService, ruleSetSelector, new ConflictEngine(),
                mock(RemoteProcessRouteService.class), mock(RemoteCalendarService.class),
                mock(RemoteClassesService.class), mock(PlnSchedulingRuleSetMapper.class),
                new SequenceSolverRegistry(List.of(new GreedyWindowSequenceSolver())), sequenceProofs,
                mock(org.mybatis.spring.SqlSessionTemplate.class), mock(PlnApsFormalContractResolver.class));
        when(ruleSetSelector.selectActiveRule()).thenReturn(activeRule());
        when(plnSchedulingTaskMapper.insert(any(PlnSchedulingTaskEntity.class))).thenAnswer(call -> {
            PlnSchedulingTaskEntity task = call.getArgument(0);
            if (task.getId() == null)
                task.setId(100L + tasks.size());
            // 完整模拟公共MyBatis插入填充，不能把缺工厂/租户的任务作为成功夹具。
            task.setTenantId(TenantContextHolder.getTenantId());
            task.setFactoryCode(FactoryContextHolder.getFactoryCode());
            tasks.put(task.getId(), task);
            return 1;
        });
        when(plnSchedulingTaskMapper.selectById(anyLong())).thenAnswer(call -> tasks.get(call.<Long>getArgument(0)));
        when(plnSchedulingTaskMapper.acquireTransactionLock(anyLong())).thenAnswer(call -> tasks.containsKey(call.<Long>getArgument(0)) ? 1L : null);
        when(plnSchedulingTaskMapper.updateById(any(PlnSchedulingTaskEntity.class))).thenReturn(1);
        when(sequenceProofs.insert(any(PlnSchedulingSequenceProof.class))).thenAnswer(call -> {
            proofs.add(call.getArgument(0));
            return 1;
        });
        when(plnSchedulingTaskDetailMapper.insert(any(PlnSchedulingTaskDetailEntity.class))).thenAnswer(call -> {
            snapshots.add(call.getArgument(0));
            return 1;
        });
        when(materialConstraintService.evaluate(anyList(), any())).thenAnswer(call -> {
            List<PlnIssueEntity> issues = call.getArgument(0);
            return issues.stream().collect(Collectors.toMap(PlnIssueEntity::getId,
                    issue -> new PlnMaterialConstraintEvaluationDTO()
                            .setIssueId(issue.getId()).setIssueNo(issue.getIssueNo())
                            .setStatus("READY").setHardBlocked(false).setBomLineageVerified(true)
                            .setBomPathFingerprint("c".repeat(64)).setLines(List.of())
                            .setOperationConstraints(List.of())
                            .setCheckedTime(LocalDateTime.of(2026, 4, 1, 8, 0))));
        });
        when(operationSchedulingService.optimizeGlobalSequence(anyList(), anyString(), anyInt(), any(), nullable(String.class))).thenAnswer(call -> {
            List<PlnIssueEntity> issues = call.getArgument(0);
            var registry = new SequenceSolverRegistry(List.of(new GreedyWindowSequenceSolver()));
            var jobs = issues.stream().map(issue -> new SequenceProblem.Job(
                    issue.getId().toString(), issue.getProductCode(), issue.getProductCode(),
                    issue.getDemandTime())).toList();
            var problem = new SequenceProblem("APS_SEQUENCE_V1", call.getArgument(1), call.getArgument(2),
                    false, "Unit orchestration fixture", "[]", jobs, Map.of());
            var solution = registry.solve(call.getArgument(4), problem);
            issues.forEach(issue -> issue.setSequenceEvidenceJson(solution.evidenceJson()));
            Map<Long, PlnIssueEntity> byId = issues.stream().collect(Collectors.toMap(PlnIssueEntity::getId, i -> i));
            return solution.solution().orderedIds().stream().map(Long::valueOf).map(byId::get).toList();
        });
        doAnswer(call -> {
            List<PlnIssueEntity> issues = call.getArgument(1);
            issues.forEach(issue -> {
                issue.setOperationScheduleStatus("SCHEDULED");
                issue.setOperationHardBlocked(false);
                issue.setOperationStartTime(issue.getScheduledDate().atTime(8, 0));
                issue.setOperationEndTime(issue.getScheduledDate().atTime(9, 0));
                issue.setOperationCount(1);
            });
            return issues.stream().collect(Collectors.toMap(PlnIssueEntity::getId, issue -> new PlnOperationScheduleEvaluationDTO().setStatus("SCHEDULED").setHardBlocked(false).setOperationCount(1)));
        }).when(operationSchedulingService).scheduleTask(anyLong(), anyList());
        var routes = mock(RemoteProcessRouteService.class);
        var route = new MdmProcessRouteEntity();
        route.setProductRate(BigDecimal.valueOf(2));
        when(routes.getDefaultByProductCode(anyString())).thenReturn(R.ok(route));
        ReflectionTestUtils.setField(plnSchedulingEngineService, "remoteProcessRouteService", routes);
        var calendar = mock(RemoteCalendarService.class);
        when(calendar.listByRange(any(), any())).thenReturn(R.ok(List.of()));
        ReflectionTestUtils.setField(plnSchedulingEngineService, "remoteCalendarService", calendar);
        var classes = mock(RemoteClassesService.class);
        var shift = new MdmClassesEntity();
        shift.setStartTime(LocalTime.of(8, 0));
        shift.setEndTime(LocalTime.of(16, 0));
        when(classes.listActive()).thenReturn(R.ok(List.of(shift)));
        ReflectionTestUtils.setField(plnSchedulingEngineService, "remoteClassesService", classes);
    }

    @AfterEach
    void clearPlanningContext() {
        SchedulingExecutionContext.clear();
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    protected PlnIssueEntity issue(Long id, String status) {
        var issue = new PlnIssueEntity();
        issue.setId(id);
        issue.setIssueNo("ISSUE-" + id);
        issue.setStatus(status);
        issue.setTenantId(1L);
        issue.setFactoryCode("F1");
        issue.setProductCode("P1");
        issue.setProductName("测试产品");
        issue.setNum(BigDecimal.valueOf(2));
        issue.setDemandTime(LocalDate.now().plusDays(7));
        issue.setIssueTime(LocalDate.now().atStartOfDay());
        issue.setEngineeringReleaseId(500L);
        issue.setEngineeringVersionNo(1);
        issue.setEngineeringContentHash("a".repeat(64));
        issue.setEngineeringRouteId(600L);
        issue.setEngineeringRouteCode("R1");
        issue.setEngineeringBomId(700L);
        issue.setEngineeringBomVersion("1");
        return issue;
    }

    protected PlnSchedulingRuleSetEntity activeRule() {
        var rule = new PlnSchedulingRuleSetEntity();
        rule.setId(1L);
        rule.setRuleCode("RULE-ACTIVE");
        rule.setRuleVersion(1);
        rule.setActive(true);
        rule.setFactoryCode("F1");
        rule.setRuleContent("{\"hardRules\":[],\"softRules\":[]}");
        return rule;
    }
}
