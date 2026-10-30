package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.plan.dto.PlanSchedulingAgentDTO;
import com.kitex.mom.plan.dto.PlnMpsRemainingReplanDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * MPS剩余工序的完整业务取证，不采用Agent的重点30条展示作为执行清单。
 *
 * <p>原根计划、MPS承诺、正式工序和实时MES状态形成稳定指纹；已开工/接收事实受保护。
 * 未结束的在制前序没有权威完成/转移释放证明时，禁止猜测后序可排时刻。
 * 本读取器加入调用方事务，不另开事务锁调用方已经持有的根计划。</p>
 */
@Component
@RequiredArgsConstructor
public class PlnMpsRemainingEvidenceReader {
    static final String FORMULA = "MPS_REMAINING_V1";
    static final String NODE_FORMULA = "MPS_REMAINING_NODE_V2";
    private static final Set<String> STATUSES = Set.of("WAIT", "UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE", "FINISH", "CLOSE");
    private final PlnIssueMapper issueMapper;
    private final PlnMpsLineMapper lineMapper;
    private final PlnSchedulingTaskMapper taskMapper;
    private final PlnSchedulingScenarioMapper scenarioMapper;
    private final PlnSchedulingOperationMapper operationMapper;
    private final RemoteMesTaskService mesService;
    private final PlnMpsExecutionEvidenceReader scopeReader;

    /**
     * A3及回退复核必须重核实际工厂write授权，不能只依靠按钮权限或工厂请求头。
     */
    void requireWriteAccess() {
        scopeReader.requireBusinessFactoryAccess("write");
    }

    /**
     * 永久回执核对也必须有实际工厂read授权，而不仅是工厂请求头。
     */
    void requireReadAccess() {
        scopeReader.requireBusinessFactoryAccess("read");
    }

    /**
     * 读取一张MPS根计划的全部正式工序，任何超限、重复或缺失映射保持不可评估。
     */
    public Proof read(Long issueId, Long sourceScenarioId, int toleranceMinutes) {
        String factory = scopeReader.actualFactory();
        Long tenant = TenantContextHolder.getTenantId();
        require(issueId != null && sourceScenarioId != null && toleranceMinutes >= 0 && toleranceMinutes <= 1440,
                "剩余工序取证须指定根计划、正式来源情景及0至1440分钟容差");
        PlnIssueEntity issue = issueMapper.selectOne(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getId, issueId).eq(PlnIssueEntity::getTenantId, tenant)
                .eq(PlnIssueEntity::getFactoryCode, factory));
        require(issue != null && issue.getMpsLineId() != null && "issue".equals(issue.getType())
                        && Set.of("1", "2").contains(StrUtil.blankToDefault(issue.getStatus(), "UNKNOWN"))
                        && Boolean.TRUE.equals(issue.getLockStatus()) && issue.getLockTaskId() != null,
                "当前实际工厂未找到已下达/生产中且正式锁定的MPS根计划");
        PlnMpsLineEntity line = lineMapper.selectOne(new LambdaQueryWrapper<PlnMpsLineEntity>()
                .eq(PlnMpsLineEntity::getId, issue.getMpsLineId()).eq(PlnMpsLineEntity::getTenantId, tenant)
                .eq(PlnMpsLineEntity::getFactoryCode, factory));
        require(line != null && Boolean.TRUE.equals(line.getActive()) && !Boolean.TRUE.equals(line.getPending())
                && Objects.equals(line.getBucketKey(), issue.getPlanningSourceKey())
                && Objects.equals(line.getProductCode(), issue.getProductCode())
                && line.getPlanQuantity() != null && issue.getNum() != null
                && line.getPlanQuantity().compareTo(issue.getNum()) == 0, "MPS桶已退役或根计划来源/原数量不一致");
        PlnSchedulingTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<PlnSchedulingTaskEntity>()
                .eq(PlnSchedulingTaskEntity::getId, issue.getLockTaskId()).eq(PlnSchedulingTaskEntity::getTenantId, tenant)
                .eq(PlnSchedulingTaskEntity::getFactoryCode, factory));
        PlnSchedulingScenarioEntity scenario = scenarioMapper.selectOne(new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                .eq(PlnSchedulingScenarioEntity::getId, sourceScenarioId).eq(PlnSchedulingScenarioEntity::getTenantId, tenant)
                .eq(PlnSchedulingScenarioEntity::getFactoryCode, factory));
        require(task != null && "official".equalsIgnoreCase(task.getMode())
                        && Set.of("SUCCESS", "PARTIAL").contains(StrUtil.blankToDefault(task.getTaskStatus(), "UNKNOWN"))
                        && Objects.equals(task.getScenarioId(), sourceScenarioId)
                        && scenario != null && Set.of("PUBLISHED", "PARTIAL").contains(scenario.getScenarioStatus()),
                "根计划当前锁定任务与已发布来源情景不一致；旧单方案不能猜测来源情景");
        List<PlnSchedulingOperationEntity> plans = operationMapper.selectList(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, task.getId()).eq(PlnSchedulingOperationEntity::getIssueId, issueId)
                .eq(PlnSchedulingOperationEntity::getTenantId, tenant).eq(PlnSchedulingOperationEntity::getFactoryCode, factory)
                .eq(PlnSchedulingOperationEntity::getLockStatus, true)
                .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq).orderByAsc(PlnSchedulingOperationEntity::getId).last("LIMIT 1001"));
        require(!plans.isEmpty() && plans.size() <= 1000, "正式工序缺失或超过1000条，禁止截断后评估");
        var response = mesService.apsOperationStates(issue.getIssueNo(), factory);
        require(response != null && response.isOk() && response.getData() != null, "MES实时工序取证不可用");
        MesApsLocalScheduleDTO.StateBundle bundle = response.getData();
        require(Objects.equals(bundle.getFactoryCode(), factory) && Objects.equals(bundle.getPlanNo(), issue.getIssueNo())
                        && Boolean.TRUE.equals(bundle.getMesTaskPresent()) && StrUtil.isNotBlank(bundle.getExecutionFingerprint())
                        && bundle.getOperations() != null && !bundle.getOperations().isEmpty() && bundle.getOperations().size() <= 1000,
                "MES必须完整返回当前精确根计划工序，不能缺工单或截断来源");

        List<String> gaps = new ArrayList<>();
        Map<String, MesApsLocalScheduleDTO.OperationState> states = new HashMap<>();
        try {
            states.putAll(PlnFrozenRouteNodeIdentity.correspondence(plans, bundle));
            PlnFrozenRouteNodeIdentity.closure(plans, List.of());
        } catch (CheckedException ex) {
            gaps.add(ex.getMessage());
        }
        LocalDateTime now = LocalDateTime.now();
        List<PlnMpsRemainingReplanDTO.Operation> operations = new ArrayList<>();
        for (var plan : plans) {
            operations.add(operation(plan, states.get(PlnFrozenRouteNodeIdentity.key(plan)), now, toleranceMinutes));
        }
        Map<String, PlnMpsRemainingReplanDTO.Operation> byCode = new HashMap<>();
        operations.forEach(row -> byCode.putIfAbsent(operationKey(row), row));
        for (var row : operations) {
            if (Boolean.TRUE.equals(row.getEligible()) && !remainingRangeSafe(operationKey(row), byCode, states, now, toleranceMinutes)) {
                row.setEligible(false).setDisposition("EXECUTION_BLOCKED")
                        .setReason("本工序及后序范围存在不可移动事实，或前序缺失/循环/仍在制；缺完成/转移释放证明不能猜可排时刻");
            }
            if ("DATA_REPAIR_REQUIRED".equals(row.getDisposition())) {
                gaps.add(row.getProcessCode() + "：" + row.getReason());
            }
        }
        // 原始计划完整JSON外加显式ISO日期，保证全字段覆盖且不因默认日期格式丢失亚秒精度。
        String raw = JSONUtil.toJsonStr(Arrays.asList(plans.stream().anyMatch(plan -> StrUtil.isNotBlank(plan.getSourceRouteNodeCode())) ? NODE_FORMULA : FORMULA, String.valueOf(tenant), factory, sourceScenarioId.toString(),
                scenario.getScenarioStatus(), toleranceMinutes,
                Arrays.asList(issue.getId().toString(), issue.getIssueNo(), issue.getStatus(), issue.getMpsLineId().toString(),
                        issue.getPlanningSourceKey(), decimal(issue.getNum()), decimal(issue.getFinishNum()),
                        decimal(issue.getUnqualifiedNum()), decimal(issue.getQualityIsolatedNum()), decimal(issue.getQualityReleasedNum()),
                        decimal(issue.getQualityRejectedNum()), decimal(issue.getScrapNum()), decimal(issue.getReworkNum()),
                        decimal(issue.getReworkCompletedNum()), decimal(issue.getReworkQualifiedNum()), decimal(issue.getReworkRejectedNum())),
                JSONUtil.toJsonStr(line), plans.stream().map(plan -> Arrays.asList(JSONUtil.toJsonStr(plan),
                        time(plan.getScheduledStartTime()), time(plan.getScheduledEndTime()))).toList(), bundle.getExecutionFingerprint(),
                bundle.getOperations().stream().filter(Objects::nonNull)
                        .sorted(Comparator.comparing(MesApsLocalScheduleDTO.OperationState::getRouteCode, Comparator.nullsFirst(String::compareTo))
                                .thenComparing(MesApsLocalScheduleDTO.OperationState::getProduceTaskId, Comparator.nullsFirst(Long::compareTo)))
                        .map(state -> stateSnapshot(state, plans.stream().anyMatch(plan -> StrUtil.isNotBlank(plan.getSourceRouteNodeCode())))).toList(),
                operations, gaps));
        List<PlanSchedulingAgentDTO.DeviationDisposition> dispositions = operations.stream().map(row ->
                new PlanSchedulingAgentDTO.DeviationDisposition().setIssueId(issueId).setIssueNo(issue.getIssueNo())
                        .setMesTaskCode(row.getTaskCode()).setProcessCode(row.getProcessCode()).setProcessName(row.getProcessName())
                        .setOperationSeq(row.getOperationSeq()).setMesStatus(row.getMesStatus()).setDisposition(row.getDisposition())
                        .setReplanEligible(gaps.isEmpty() && row.getEligible()).setReason(row.getReason())).toList();
        var effect = new PlanSchedulingAgentDTO.EffectEvidence().setAvailable(gaps.isEmpty()).setScenarioId(sourceScenarioId)
                .setScenarioNo(scenario.getScenarioNo()).setScenarioStatus(scenario.getScenarioStatus()).setToleranceMinutes(toleranceMinutes)
                .setEffectFingerprint(PlnMpsExecutionEvidenceReader.hash(raw)).setDeviationDispositions(dispositions)
                .setDeviationTruncated(false).setDataGaps(gaps).setCapturedTime(now);
        return new Proof(effect, operations, gaps, bundle.getExecutionFingerprint(), raw);
    }

    private PlnMpsRemainingReplanDTO.Operation operation(PlnSchedulingOperationEntity plan,
                                                         MesApsLocalScheduleDTO.OperationState state, LocalDateTime now, int tolerance) {
        var row = new PlnMpsRemainingReplanDTO.Operation().setOperationId(String.valueOf(plan.getId()))
                .setProcessCode(plan.getProcessCode()).setProcessName(plan.getProcessName()).setOperationSeq(plan.getOperationSeq())
                .setSourceRouteNodeCode(plan.getSourceRouteNodeCode()).setPredecessorNodeCodes(plan.getPredecessorNodeCodes())
                .setPredecessorCodes(plan.getPredecessorCodes()).setPlannedQuantity(decimal(plan.getPlannedQuantity()))
                .setPlannedStart(time(plan.getScheduledStartTime())).setPlannedEnd(time(plan.getScheduledEndTime())).setEligible(false);
        if (state == null) return disposition(row, "DATA_REPAIR_REQUIRED", "缺少精确MES工序映射");
        row.setTaskCode(state.getTaskCode()).setMesStatus(state.getStatus()).setCompletedQuantity(decimal(state.getCompleteQuantity()))
                .setActualStart(time(state.getActualStartTime())).setActualFinish(time(state.getActualFinishTime()));
        if (plan.getScheduledStartTime() == null || plan.getScheduledEndTime() == null
                || !plan.getScheduledEndTime().isAfter(plan.getScheduledStartTime()) || Boolean.TRUE.equals(plan.getHardBlocked())
                || plan.getPlannedQuantity() == null || plan.getPlannedQuantity().signum() <= 0
                || state.getProduceTaskId() == null || StrUtil.isBlank(state.getTaskCode())
                || StrUtil.isBlank(state.getProduceTaskCode()) || state.getMovable() == null || state.getRouteOrder() == null
                || !Objects.equals(state.getRouteOrder(), plan.getOperationSeq())
                || !STATUSES.contains(StrUtil.blankToDefault(state.getStatus(), "UNKNOWN"))
                || state.getCompleteQuantity() == null || state.getCompleteQuantity().signum() < 0
                || state.getCompleteQuantity().compareTo(plan.getPlannedQuantity()) > 0
                || "FINISH".equals(state.getStatus()) && state.getActualFinishTime() == null
                || !Objects.equals(plan.getScheduledStartTime(), state.getPlannedStartTime())
                || !Objects.equals(plan.getScheduledEndTime(), state.getPlannedEndTime())
                || state.getActualStartTime() != null && state.getActualStartTime().isAfter(now)
                || state.getActualFinishTime() != null && state.getActualFinishTime().isAfter(now)
                || state.getActualFinishTime() != null && (state.getActualStartTime() == null
                || state.getActualFinishTime().isBefore(state.getActualStartTime()))
                || state.getActualStartTime() == null && (state.getCompleteQuantity().signum() > 0
                || Set.of("PRODUCT", "PAUSE", "FINISH").contains(state.getStatus()))) {
            return disposition(row, "DATA_REPAIR_REQUIRED", "工序状态、序号、数量、PLAN/MES计划时刻或实际时刻不可完整核验");
        }
        if ("PAUSE".equals(state.getStatus()))
            return disposition(row, "EXECUTION_BLOCKED", "先解除MES现场暂停和生产门，不以重排代替复产");
        if (state.getActualStartTime() != null || state.getActualFinishTime() != null || state.getCompleteQuantity().signum() > 0
                || !Set.of("WAIT", "UN_RECEIVE").contains(state.getStatus())) {
            return disposition(row, "EXECUTION_LOCKED_REVIEW", "已接收/执行工序保留原事实，不纳入移动范围");
        }
        if (!Boolean.TRUE.equals(state.getMovable()))
            return disposition(row, "EXECUTION_BLOCKED", StrUtil.blankToDefault(state.getBlockedReason(), "MES未证明允许移动"));
        if (!now.isBefore(plan.getScheduledStartTime().plusMinutes(tolerance))) {
            return row.setEligible(true).setDisposition("LOCAL_REPLAN_CANDIDATE").setReason("未接收/开工且已超开工容差，可评估直接工序及其未执行后序");
        }
        return disposition(row, "OBSERVE", "尚未超原正式开工容差，继续观察，不扩大偏差范围");
    }

    /**
     * 候选会移动直接工序及全部后序，因此必须核验整个闭包。
     * 例如后序还依赖另一条在制支路，不能只核对用户勾选的直接工序就套用旧预计完工。
     */
    private boolean remainingRangeSafe(String direct, Map<String, PlnMpsRemainingReplanDTO.Operation> operations,
                                       Map<String, MesApsLocalScheduleDTO.OperationState> states,
                                       LocalDateTime now, int tolerance) {
        Set<String> affected = new HashSet<>(Set.of(direct));
        boolean changed;
        do {
            changed = false;
            for (var row : operations.values()) {
                if (!affected.contains(operationKey(row))
                        && Arrays.stream(StrUtil.blankToDefault(predecessorKeys(row), "").split("[,;]"))
                        .map(String::trim).anyMatch(affected::contains)) {
                    changed |= affected.add(operationKey(row));
                }
            }
        } while (changed);
        for (String code : affected) {
            var row = operations.get(code);
            var state = states.get(code);
            if (row == null || state == null || "DATA_REPAIR_REQUIRED".equals(row.getDisposition())
                    || !Boolean.TRUE.equals(state.getMovable()) || state.getActualStartTime() != null
                    || state.getActualFinishTime() != null || state.getCompleteQuantity() == null
                    || state.getCompleteQuantity().signum() != 0
                    || !Set.of("WAIT", "UN_RECEIVE").contains(StrUtil.blankToDefault(state.getStatus(), "UNKNOWN"))
                    || !predecessorsSafe(code, operations, new HashSet<>(), affected, now, tolerance)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查全部上游，防止把正在加工的前序的旧预计完工当成当前可靠完工。
     */
    private boolean predecessorsSafe(String code, Map<String, PlnMpsRemainingReplanDTO.Operation> operations,
                                     Set<String> visiting, Set<String> affected, LocalDateTime now, int tolerance) {
        if (!visiting.add(code)) return false;
        var row = operations.get(code);
        if (row == null) return false;
        for (String predecessor : StrUtil.blankToDefault(predecessorKeys(row), "").split("[,;]")) {
            if (predecessor.isBlank()) continue;
            var parent = operations.get(predecessor.trim());
            if (parent == null || "DATA_REPAIR_REQUIRED".equals(parent.getDisposition())
                    || "PAUSE".equals(parent.getMesStatus()) || "CLOSE".equals(parent.getMesStatus())
                    || parent.getActualStart() != null && parent.getActualFinish() == null
                    || parent.getActualFinish() != null && (!"FINISH".equals(parent.getMesStatus())
                    || parent.getCompletedQuantity() == null || parent.getPlannedQuantity() == null
                    || new BigDecimal(parent.getCompletedQuantity()).compareTo(new BigDecimal(parent.getPlannedQuantity())) != 0)
                    // 未开工但旧计划已逾期的前序必须一并重排，不能留在范围外冒充已完成。
                    || parent.getActualStart() == null && !affected.contains(predecessor.trim())
                    && (parent.getPlannedStart() == null || parent.getPlannedEnd() == null
                    || !LocalDateTime.parse(parent.getPlannedStart()).plusMinutes(tolerance).isAfter(now)
                    || !LocalDateTime.parse(parent.getPlannedEnd()).isAfter(now))
                    || !predecessorsSafe(predecessor.trim(), operations, visiting, affected, now, tolerance))
                return false;
        }
        visiting.remove(code);
        return true;
    }

    private PlnMpsRemainingReplanDTO.Operation disposition(PlnMpsRemainingReplanDTO.Operation row, String disposition, String reason) {
        return row.setDisposition(disposition).setReason(reason);
    }

    private String operationKey(PlnMpsRemainingReplanDTO.Operation row) {
        return StrUtil.isNotBlank(row.getSourceRouteNodeCode()) ? row.getSourceRouteNodeCode() : row.getProcessCode();
    }

    private String predecessorKeys(PlnMpsRemainingReplanDTO.Operation row) {
        return StrUtil.isNotBlank(row.getSourceRouteNodeCode()) ? row.getPredecessorNodeCodes() : row.getPredecessorCodes();
    }

    /**
     * Existing V1 state positions remain byte-compatible; NODE_V2 appends only the node identity.
     */
    private List<Object> stateSnapshot(MesApsLocalScheduleDTO.OperationState state, boolean nodeContract) {
        List<Object> result = new ArrayList<>(Arrays.asList(state.getProduceTaskId() == null ? null : state.getProduceTaskId().toString(),
                state.getProduceTaskCode(), state.getTaskCode(), state.getRouteCode(), state.getRouteOrder(), state.getStatus(),
                time(state.getActualStartTime()), time(state.getActualFinishTime()), decimal(state.getCompleteQuantity()),
                time(state.getPlannedStartTime()), time(state.getPlannedEndTime()), state.getMovable(), state.getBlockedReason()));
        if (nodeContract) result.add(state.getSourceRouteNodeCode());
        return result;
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String time(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    /**
     * 原始工序事实与内部效果契约同时返回，业务端不调用模型或自动改计划。
     */
    public record Proof(PlanSchedulingAgentDTO.EffectEvidence effect,
                        List<PlnMpsRemainingReplanDTO.Operation> operations,
                        List<String> gaps, String mesFingerprint, String snapshot) {
    }
}
