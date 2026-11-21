package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mes.batch.BatchGenerator;
import com.kitex.mom.mes.batch.BatchGeneratorService;
import com.kitex.mom.mes.dto.MesQualityGateDecision;
import com.kitex.mom.mes.dto.MesQualityGateItemDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.QualityGateModeEnum;
import com.kitex.mom.mes.enums.QualityGateStatusEnum;
import com.kitex.mom.mes.mapper.MesChangePointHandleMapper;
import com.kitex.mom.mes.service.MesInspectionGenRecordService;
import com.kitex.mom.mes.service.MesInspectionRuleService;
import com.kitex.mom.mes.service.MesQualityGateService;
import com.kitex.mom.mes.support.MesPatrolInterval;
import com.kitex.mom.qms.api.feign.RemoteProcedureService;
import com.kitex.mom.qms.api.feign.RemoteQualityStandardService;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import com.kitex.mom.qms.enums.InspectionMethodEnum;
import com.kitex.mom.qms.enums.InspectionTaskResultEnum;
import com.kitex.mom.qms.enums.InspectionTaskTypeEnum;
import com.kitex.mom.qms.enums.ProcedureLabelTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MesQualityGateServiceImpl implements MesQualityGateService {

    private static final Set<String> PASS_RESULTS =
            Set.of(
                    InspectionTaskResultEnum.QUALIFIED.name(),
                    InspectionTaskResultEnum.EXEMPTION.name(),
                    InspectionTaskResultEnum.RELEASE.name(),
                    InspectionTaskResultEnum.RECEIVE.name());

    private final MesQualityGateSourceEvidence sourceEvidence;
    private final MesInspectionEventProjection eventProjection;
    private final MesInspectionRuleService inspectionRuleService;
    private final MesInspectionGenRecordService inspectionGenRecordService;
    private final RemoteProcedureService remoteProcedureService;
    private final MesQmsInspectionCoordinator qmsInspectionCoordinator;
    private final RemoteQualityStandardService remoteQualityStandardService;
    private final RemoteClassesService remoteClassesService;
    private final RemoteParamService remoteParamService;
    private final BatchGeneratorService batchGeneratorService;
    private final MesChangePointHandleMapper changePointHandleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesQualityGateDecision eventSnapshot(
            String code, String phase, Long recordId, Long eventId, Date time) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) throw new CheckedException("冻结质量聚合须加入实际业务事务");
        eventProjection.installed();
        if (recordId != null || eventId != null) {
            if (recordId == null || eventId == null || time == null)
                throw new CheckedException("应用候选缺少完整身份");
            var candidate =
                    inspectionGenRecordService.getOne(
                            recordScope().eq(MesInspectionGenRecordEntity::getId, recordId));
            if (candidate == null || !Objects.equals(candidate.getProduceTaskCode(), code))
                throw new CheckedException("应用候选不属于实际工序");
            eventProjection.candidate(candidate, eventId, time);
        }
        var process = requireProduceTask(code);
        var context = new GateContext(recordId, eventId, time, false, true);
        var types =
                "REPORT".equals(phase)
                        ? Set.of("FIRST", "MIDDLE")
                        : Set.of("FIRST", "MIDDLE", "LAST");
        var ordinary = evaluate(process, phase, types, false, context);
        if ("NONE".equals(ordinary.getGateMode()) && ordinary.getItems().isEmpty())
            ordinary.setPassed(false);
        return mergeIntensifiedGate(
                ordinary, evaluateIntensifiedReportGate(process, false, context));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision checkReport(String produceTaskCode) {
        MesProduceTaskEntity produceTask = requireProduceTask(produceTaskCode);
        MesQualityGateDecision standard =
                evaluate(
                        produceTask,
                        "REPORT",
                        Set.of(
                                ProcedureLabelTypeEnum.FIRST.name(),
                                ProcedureLabelTypeEnum.MIDDLE.name()),
                        true);
        return mergeIntensifiedGate(standard, evaluateIntensifiedReportGate(produceTask, true));
    }

    private MesQualityGateDecision mergeIntensifiedGate(
            MesQualityGateDecision standard, MesQualityGateDecision intensified) {
        if (intensified.getItems().isEmpty() && Boolean.TRUE.equals(intensified.getAllowed()))
            return standard;
        boolean ordinarySatisfied =
                Boolean.TRUE.equals(standard.getPassed()) || "NONE".equals(standard.getGateMode());
        boolean ordinaryNone = "NONE".equals(standard.getGateMode());
        standard.getItems().addAll(intensified.getItems());
        standard.setAllowed(
                        Boolean.TRUE.equals(standard.getAllowed())
                                && Boolean.TRUE.equals(intensified.getAllowed()))
                .setPassed(ordinarySatisfied && Boolean.TRUE.equals(intensified.getPassed()))
                .setGateMode(
                        ordinaryNone || !Boolean.TRUE.equals(intensified.getAllowed())
                                ? QualityGateModeEnum.BLOCK.name()
                                : standard.getGateMode());
        if (Boolean.TRUE.equals(standard.getPassed())) standard.setMessage("所需检验已放行");
        else if (!Boolean.TRUE.equals(intensified.getAllowed()))
            standard.setMessage(standard.getMessage() + "；" + intensified.getMessage());
        return standard;
    }

    private MesQualityGateItemDTO missingEvidence(
            MesProduceTaskEntity process, String type, String reason) {
        return new MesQualityGateItemDTO()
                .setTaskCode(process.getTaskCode())
                .setProduceTaskCode(process.getCode())
                .setInspectionType(type)
                .setGenFlag("0")
                .setGateStatus(QualityGateStatusEnum.PENDING.name())
                .setGenRemark(reason)
                .setSyncMessage(reason);
    }

    /**
     * 变化点加严巡检是独立生产控制，不依赖物料普通质检规则是否配置了中检。
     */
    private MesQualityGateDecision evaluateIntensifiedReportGate(
            MesProduceTaskEntity produceTask, boolean registerFailure) {
        return evaluateIntensifiedReportGate(produceTask, registerFailure, GateContext.normal());
    }

    private MesQualityGateDecision evaluateIntensifiedReportGate(
            MesProduceTaskEntity produceTask, boolean registerFailure, GateContext context) {
        MesQualityGateDecision decision =
                new MesQualityGateDecision()
                        .setGateMode(QualityGateModeEnum.BLOCK.name())
                        .setPhase("CHANGE_POINT_MONITOR")
                        .setAllowed(true)
                        .setPassed(true)
                        .setMessage("当前无到期的变化点加严巡检");
        List<MesChangePointHandleEntity> monitoring =
                changePointHandleMapper.selectList(
                        new LambdaQueryWrapper<MesChangePointHandleEntity>()
                                .eq(
                                        MesChangePointHandleEntity::getTenantId,
                                        TenantContextHolder.getTenantId())
                                .eq(MesChangePointHandleEntity::getStatus, "M")
                                .eq(
                                        MesChangePointHandleEntity::getProduceTaskCode,
                                        produceTask.getCode())
                                .eq(
                                        MesChangePointHandleEntity::getFactoryCode,
                                        produceTask.getFactoryCode()));
        if (monitoring.isEmpty()) return decision;

        List<String> problems = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        List<Long> recordIds =
                monitoring.stream()
                        .map(MesChangePointHandleEntity::getIntensifiedInspectionRecordId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        Map<Long, MesInspectionGenRecordEntity> records =
                recordIds.isEmpty()
                        ? Map.of()
                        : inspectionGenRecordService
                        .list(
                                recordScope()
                                        .in(MesInspectionGenRecordEntity::getId, recordIds))
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        MesInspectionGenRecordEntity::getId,
                                        Function.identity(),
                                        (left, right) -> left));
        List<MesInspectionGenRecordEntity> involved = new ArrayList<>();
        for (MesChangePointHandleEntity event : monitoring) {
            if (event.getIntensifiedInspectionRecordId() == null) {
                if (event.getIntensifiedInspectionNextTime() == null
                        || !event.getIntensifiedInspectionNextTime().isAfter(now)) {
                    problems.add("变化点[" + event.getEventCode() + "]加严巡检已到期但尚未生成");
                    decision.getItems()
                            .add(
                                    missingEvidence(
                                            produceTask,
                                            "MIDDLE",
                                            "变化点[" + event.getEventCode() + "]加严巡检尚未生成"));
                }
                continue;
            }
            MesInspectionGenRecordEntity record =
                    records.get(event.getIntensifiedInspectionRecordId());
            if (record == null
                    || !Objects.equals(record.getProduceTaskCode(), produceTask.getCode())
                    || !Objects.equals(record.getFactoryCode(), produceTask.getFactoryCode())) {
                problems.add("变化点[" + event.getEventCode() + "]加严巡检来源不存在或归属不一致");
                decision.getItems()
                        .add(
                                missingEvidence(
                                        produceTask,
                                        "MIDDLE",
                                        "变化点[" + event.getEventCode() + "]加严巡检来源待核验"));
                continue;
            }
            involved.add(record);
        }
        Map<Long, QmsInspectionProcedureTaskEntity> qmsTasks =
                loadQmsTasks(involved, problems, context);
        for (MesInspectionGenRecordEntity record : involved) {
            QualityGateStatusEnum status =
                    syncRecord(
                            record,
                            record.getInspectionId() == null
                                    ? null
                                    : qmsTasks.get(record.getInspectionId()),
                            context);
            decision.getItems().add(toItem(record));
            if (status != QualityGateStatusEnum.PASSED) {
                problems.add(
                        "加严巡检["
                                + Optional.ofNullable(record.getInspectionId())
                                .map(String::valueOf)
                                .orElse("未生成")
                                + "]"
                                + statusMessage(record, status));
                if (registerFailure) registerBlock(record);
            }
        }
        if (problems.isEmpty()) {
            return decision.setMessage("当前变化点加严巡检已放行");
        }
        return decision.setAllowed(false).setPassed(false).setMessage(String.join("；", problems));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision checkFinish(
            MesProduceTaskEntity produceTask, MesTaskEntity task) {
        produceTask = requireProduceTask(produceTask == null ? null : produceTask.getCode());
        task = sourceEvidence.root(produceTask);
        MesQualityGateDecision standard;
        if (!"1".equals(produceTask.getIsInspection())) {
            standard =
                    new MesQualityGateDecision()
                            .setAllowed(true)
                            .setGateMode(QualityGateModeEnum.NONE.name())
                            .setPassed(true)
                            .setPhase("FINISH")
                            .setMessage("当前工序不需要质检");
        } else {
            MesInspectionRuleEntity rule = configuredRule(produceTask);
            QualityGateModeEnum mode = gateMode(rule);
            if (mode != QualityGateModeEnum.NONE
                    && containsType(rule, ProcedureLabelTypeEnum.LAST)) {
                ensureLastInspection(produceTask, task, rule);
            }
            // 完工既要满足末检，也不能绕过此前仍未放行的首检/中检。
            standard =
                    evaluate(
                            produceTask,
                            "FINISH",
                            Set.of(
                                    ProcedureLabelTypeEnum.FIRST.name(),
                                    ProcedureLabelTypeEnum.MIDDLE.name(),
                                    ProcedureLabelTypeEnum.LAST.name()),
                            true);
        }
        // 变化点加严巡检是独立生产控制，即使普通质检开关关闭也不能通过直接完工绕过。
        return mergeIntensifiedGate(standard, evaluateIntensifiedReportGate(produceTask, true));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision sync(String produceTaskCode) {
        MesProduceTaskEntity produceTask = requireProduceTask(produceTaskCode);
        MesQualityGateDecision standard =
                evaluate(
                        produceTask,
                        "ALL",
                        Set.of(
                                ProcedureLabelTypeEnum.FIRST.name(),
                                ProcedureLabelTypeEnum.MIDDLE.name(),
                                ProcedureLabelTypeEnum.LAST.name()),
                        false);
        return mergeIntensifiedGate(standard, evaluateIntensifiedReportGate(produceTask, false));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision retryGeneration(Long recordId) {
        var record = generatePendingRecord(recordId, false);
        return sync(record.getProduceTaskCode());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Long deliverPendingGeneration(Long recordId) {
        var record = generatePendingRecord(recordId, true);
        return "1".equals(record.getGenFlag())
                        && record.getInspectionId() != null
                        && record.getInspectionId() > 0
                ? record.getInspectionId()
                : null;
    }

    private MesInspectionGenRecordEntity generatePendingRecord(
            Long recordId, boolean acceptExisting) {
        MesInspectionGenRecordEntity record =
                inspectionGenRecordService.getOne(
                        recordScope()
                                .eq(MesInspectionGenRecordEntity::getId, recordId)
                                .last("FOR UPDATE"));
        if (record == null) {
            throw new IllegalArgumentException("质检生成记录不存在");
        }
        if ("1".equals(record.getGenFlag()) && record.getInspectionId() != null) {
            if (acceptExisting && record.getInspectionId() > 0) return record;
            throw new IllegalArgumentException("质检任务已生成，只需同步结论，无需重试生成");
        }
        MesProduceTaskEntity produceTask = requireProduceTask(record.getProduceTaskCode());
        MesTaskEntity task = sourceEvidence.root(produceTask);
        if (task == null) {
            throw new IllegalArgumentException("未找到质检记录对应的生产工单");
        }
        String type = normalizeType(record.getInspectionType());
        if (!Set.of(
                        ProcedureLabelTypeEnum.FIRST.name(),
                        ProcedureLabelTypeEnum.MIDDLE.name(),
                        ProcedureLabelTypeEnum.LAST.name())
                .contains(type)) {
            throw new IllegalArgumentException("不支持重试的质检类型：" + record.getInspectionType());
        }
        generateInspection(record, produceTask, task, type);
        return record;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision createChangePointFirstInspection(
            String produceTaskCode, String sourceRequestId, String ruleCode, String ruleName) {
        return createChangePointInspection(
                produceTaskCode,
                sourceRequestId,
                ruleCode,
                ruleName,
                ProcedureLabelTypeEnum.FIRST.name());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision createChangePointIntensifiedInspection(
            String produceTaskCode, String sourceRequestId, String ruleCode, String ruleName) {
        return createChangePointInspection(
                produceTaskCode,
                sourceRequestId,
                ruleCode,
                ruleName,
                ProcedureLabelTypeEnum.MIDDLE.name());
    }

    private MesQualityGateDecision createChangePointInspection(
            String produceTaskCode,
            String sourceRequestId,
            String ruleCode,
            String ruleName,
            String inspectionType) {
        if (StrUtil.isBlank(sourceRequestId)) {
            throw new IllegalArgumentException("变化点质检来源请求号不能为空");
        }
        MesProduceTaskEntity produceTask = requireProduceTask(produceTaskCode);
        MesTaskEntity task = sourceEvidence.root(produceTask);
        if (task == null) {
            throw new IllegalArgumentException("未找到变化点质检对应的生产工单");
        }
        MesInspectionGenRecordEntity record =
                inspectionGenRecordService.getOne(
                        recordScope()
                                .eq(
                                        MesInspectionGenRecordEntity::getSourceRequestId,
                                        sourceRequestId)
                                .orderByDesc(MesInspectionGenRecordEntity::getId)
                                .last("LIMIT 1"),
                        false);
        if (record == null) {
            record =
                    new MesInspectionGenRecordEntity()
                            .setTaskCode(produceTask.getTaskCode())
                            .setProduceTaskCode(produceTask.getCode())
                            .setRuleCode(ruleCode)
                            .setRuleName(ruleName)
                            .setInspectionType(inspectionType)
                            .setSourceRequestId(sourceRequestId)
                            .setGenTime(LocalDateTime.now())
                            .setGenFlag("0")
                            .setGateStatus(QualityGateStatusEnum.ERROR.name())
                            .setBlockedCount(0)
                            .setFactoryCode(produceTask.getFactoryCode());
            record.setTenantId(TenantContextHolder.getTenantId());
            if (!inspectionGenRecordService.save(record))
                throw new CheckedException("质检生成记录保存失败，未发送创建请求");
        }
        if (!"1".equals(record.getGenFlag()) || record.getInspectionId() == null) {
            generateInspection(record, produceTask, task, inspectionType);
        }
        return syncChangePointInspection(record.getId());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision syncChangePointInspection(Long recordId) {
        MesInspectionGenRecordEntity record =
                inspectionGenRecordService.getOne(
                        recordScope().eq(MesInspectionGenRecordEntity::getId, recordId));
        if (record == null) {
            return new MesQualityGateDecision()
                    .setAllowed(false)
                    .setPassed(false)
                    .setGateMode("BLOCK")
                    .setPhase("CHANGE_POINT")
                    .setMessage("变化点质检记录不存在");
        }
        List<String> problems = new ArrayList<>();
        Map<Long, QmsInspectionProcedureTaskEntity> tasks = loadQmsTasks(List.of(record), problems);
        QualityGateStatusEnum status =
                syncRecord(
                        record,
                        record.getInspectionId() == null
                                ? null
                                : tasks.get(record.getInspectionId()));
        boolean passed = status == QualityGateStatusEnum.PASSED && problems.isEmpty();
        String inspectionLabel = typeLabel(record.getInspectionType());
        String message =
                passed
                        ? "变化点" + inspectionLabel + "已放行"
                        : (problems.isEmpty()
                        ? "变化点" + inspectionLabel + statusMessage(record, status)
                        : String.join("；", problems));
        return new MesQualityGateDecision()
                .setAllowed(passed)
                .setPassed(passed)
                .setGateMode("BLOCK")
                .setPhase("CHANGE_POINT")
                .setMessage(message)
                .setItems(List.of(toItem(record)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision retryChangePointInspection(Long recordId) {
        MesInspectionGenRecordEntity record =
                inspectionGenRecordService.getOne(
                        recordScope().eq(MesInspectionGenRecordEntity::getId, recordId));
        if (record == null) {
            throw new IllegalArgumentException("变化点质检记录不存在");
        }
        if ("1".equals(record.getGenFlag()) && record.getInspectionId() != null) {
            return syncChangePointInspection(recordId);
        }
        MesProduceTaskEntity produceTask = requireProduceTask(record.getProduceTaskCode());
        MesTaskEntity task = sourceEvidence.root(produceTask);
        if (task == null) {
            throw new IllegalArgumentException("未找到变化点质检对应的生产工单");
        }
        String type = normalizeType(record.getInspectionType());
        if (!Set.of("FIRST", "MIDDLE").contains(type)) throw new CheckedException("变化点质检重试阶段不可核验");
        generateInspection(record, produceTask, task, type);
        return syncChangePointInspection(recordId);
    }

    private MesQualityGateDecision evaluate(
            MesProduceTaskEntity produceTask,
            String phase,
            Set<String> phaseTypes,
            boolean registerBlock) {
        return evaluate(produceTask, phase, phaseTypes, registerBlock, GateContext.normal());
    }

    private MesQualityGateDecision evaluate(
            MesProduceTaskEntity produceTask,
            String phase,
            Set<String> phaseTypes,
            boolean registerBlock,
            GateContext context) {
        MesQualityGateDecision decision =
                new MesQualityGateDecision()
                        .setGateMode(QualityGateModeEnum.NONE.name())
                        .setPhase(phase);
        if (!"1".equals(produceTask.getIsInspection())) {
            return decision.setAllowed(true)
                    .setGateMode(QualityGateModeEnum.NONE.name())
                    .setPassed(true)
                    .setMessage("当前工序不需要质检");
        }
        MesInspectionRuleEntity rule = configuredRule(produceTask);
        QualityGateModeEnum mode = gateMode(rule);
        decision.setGateMode(mode.name());
        if (mode == QualityGateModeEnum.NONE) {
            return decision.setAllowed(true).setPassed(false).setMessage("质量门未启用；未提供检验放行依据");
        }
        Set<String> configuredTypes = inspectionTypes(rule);
        Set<String> requiredTypes =
                phaseTypes.stream()
                        .filter(configuredTypes::contains)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        List<MesInspectionGenRecordEntity> records =
                inspectionGenRecordService.list(
                        recordScope()
                                .eq(
                                        MesInspectionGenRecordEntity::getProduceTaskCode,
                                        produceTask.getCode())
                                .orderByAsc(MesInspectionGenRecordEntity::getGenTime)
                                .orderByAsc(MesInspectionGenRecordEntity::getId)
                                .last("LIMIT 5001"));
        if (records.size() > 5000) throw new CheckedException("工序质检记录超过5000条，请先治理，不能截断依据");
        List<MesInspectionGenRecordEntity> involved =
                records.stream()
                        .filter(
                                record ->
                                        configuredTypes.contains(
                                                normalizeType(record.getInspectionType())))
                        .toList();
        List<String> problems = new ArrayList<>();
        List<String> evidenceProblems = new ArrayList<>();
        if (configuredTypes.contains(ProcedureLabelTypeEnum.FIRST.name())
                && involved.stream()
                .noneMatch(
                        record ->
                                ProcedureLabelTypeEnum.FIRST
                                        .name()
                                        .equals(
                                                normalizeType(
                                                        record.getInspectionType())))) {
            evidenceProblems.add("首检任务未生成");
            if (requiredTypes.contains("FIRST")) problems.add("首检任务未生成");
            decision.getItems().add(missingEvidence(produceTask, "FIRST", "首检任务未生成"));
        }
        if (configuredTypes.contains(ProcedureLabelTypeEnum.LAST.name())
                && involved.stream()
                .noneMatch(
                        record ->
                                ProcedureLabelTypeEnum.LAST
                                        .name()
                                        .equals(
                                                normalizeType(
                                                        record.getInspectionType())))) {
            evidenceProblems.add("末检任务未生成");
            if (requiredTypes.contains("LAST")) problems.add("末检任务未生成");
            decision.getItems().add(missingEvidence(produceTask, "LAST", "末检任务未生成"));
        }
        if (requiredTypes.contains(ProcedureLabelTypeEnum.MIDDLE.name())) {
            String waiting = pendingPatrol(involved, LocalDateTime.now(), context.localOnly());
            if (waiting != null) {
                evidenceProblems.add(waiting);
                problems.add(waiting);
                decision.getItems().add(missingEvidence(produceTask, ProcedureLabelTypeEnum.MIDDLE.name(), waiting));
            }
        }

        Map<Long, QmsInspectionProcedureTaskEntity> qmsTasks =
                loadQmsTasks(involved, evidenceProblems, context);
        for (MesInspectionGenRecordEntity record : involved) {
            QmsInspectionProcedureTaskEntity qmsTask =
                    record.getInspectionId() == null
                            ? null
                            : qmsTasks.get(record.getInspectionId());
            QualityGateStatusEnum gateStatus = syncRecord(record, qmsTask, context);
            if (gateStatus != QualityGateStatusEnum.PASSED) {
                String problem =
                        typeLabel(record.getInspectionType())
                                + "["
                                + Optional.ofNullable(record.getInspectionId())
                                .map(String::valueOf)
                                .orElse("未生成")
                                + "]"
                                + statusMessage(record, gateStatus);
                evidenceProblems.add(problem);
                if (requiredTypes.contains(normalizeType(record.getInspectionType()))) {
                    problems.add(problem);
                    if (registerBlock && mode == QualityGateModeEnum.BLOCK) registerBlock(record);
                }
            }
            decision.getItems().add(toItem(record));
        }
        boolean passed = evidenceProblems.isEmpty() && !involved.isEmpty();
        boolean allowed = problems.isEmpty() || mode == QualityGateModeEnum.WARN;
        String message =
                passed
                        ? "质量门已放行"
                        : evidenceProblems.isEmpty()
                        ? "暂无检验判定依据，未产生放行数量"
                        : String.join("；", evidenceProblems);
        if (!problems.isEmpty() && mode == QualityGateModeEnum.WARN)
            message = "质量门预警（不阻断）：" + String.join("；", problems);
        return decision.setAllowed(allowed).setPassed(passed).setMessage(message);
    }

    /**
     * 到期排程在生成记录落库前也属于待检。正式事件处理只使用已保存的时刻，
     * 只有班次编码的旧排程在无法离线核对当天开始时间时暂缓放行。
     */
    private String pendingPatrol(List<MesInspectionGenRecordEntity> records, LocalDateTime observedAt, boolean localOnly) {
        var generated = records.stream()
                .filter(record -> ProcedureLabelTypeEnum.MIDDLE.name().equals(normalizeType(record.getInspectionType())))
                .map(MesInspectionGenRecordEntity::getSourceRequestId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (var record : records) {
            var due = record.getNextTime();
            String shift = record.getNextShift();
            if ((due == null && StrUtil.isBlank(shift)) || generated.contains(MesPatrolInterval.requestId(record))) {
                continue;
            }
            if (due == null) {
                try {
                    var day = MesPatrolInterval.shiftDate(shift);
                    if (day.isAfter(observedAt.toLocalDate())) {
                        continue;
                    }
                    if (day.isBefore(observedAt.toLocalDate())) {
                        return "中检已到期，等待生成检验任务";
                    }
                    if (localOnly) {
                        return "原中检班次开始时刻待核对";
                    }
                    due = MesPatrolInterval.originalShift(remoteClassesService.getClassesByCode(shift, 1), shift).startsAt();
                } catch (RuntimeException failure) {
                    return "中检排程暂无法核对";
                }
            }
            if (!due.isAfter(observedAt)) {
                return "中检已到期，等待生成检验任务";
            }
        }
        return null;
    }

    private Map<Long, QmsInspectionProcedureTaskEntity> loadQmsTasks(
            List<MesInspectionGenRecordEntity> records, List<String> problems) {
        return loadQmsTasks(records, problems, GateContext.normal());
    }

    private Map<Long, QmsInspectionProcedureTaskEntity> loadQmsTasks(
            List<MesInspectionGenRecordEntity> records,
            List<String> problems,
            GateContext context) {
        Map<Long, QmsInspectionProcedureTaskEntity> result = new HashMap<>();
        Map<String, Boolean> controlled = new HashMap<>();
        for (var record : records) {
            boolean formal =
                    context.localOnly()
                            || controlled.computeIfAbsent(
                            record.getProduceTaskCode(), eventProjection::controlled);
            var evidence =
                    eventProjection.resolve(
                            record, formal, context.recordId(), context.eventId(), context.time());
            eventProjection.attach(record, evidence);
            // mocked legacy fixtures do not synthesize event evidence; legacy behavior remains
            // explicit.
            if (evidence == null) record.setQualityEventControlled(false);
            if (evidence != null && evidence.task() != null)
                result.put(record.getInspectionId(), evidence.task());
        }
        List<Long> ids =
                records.stream()
                        .filter(record -> !Boolean.TRUE.equals(record.getQualityEventControlled()))
                        .filter(record -> "1".equals(record.getGenFlag()))
                        .map(MesInspectionGenRecordEntity::getInspectionId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        if (ids.isEmpty()) {
            return result;
        }
        try {
            R<List<QmsInspectionProcedureTaskEntity>> response =
                    remoteProcedureService.getTaskByIds(ids);
            if (response == null || response.getCode() != 0 || response.getData() == null)
                throw new IllegalStateException("QMS响应未成功");
            List<QmsInspectionProcedureTaskEntity> tasks = response.getData();
            if (tasks.size() > ids.size()
                    || tasks.stream().anyMatch(task -> task == null || !ids.contains(task.getId())))
                throw new IllegalStateException("QMS返回未知或重复检验身份");
            Map<Long, QmsInspectionProcedureTaskEntity> remote =
                    tasks.stream()
                            .collect(
                                    Collectors.toMap(
                                            QmsInspectionProcedureTaskEntity::getId,
                                            Function.identity()));
            result.putAll(remote);
            return result;
        } catch (Exception exception) {
            log.warn(
                    "同步QMS工序质检任务未确认，数量={}，异常类型={}",
                    ids.size(),
                    exception.getClass().getSimpleName());
            problems.add("QMS质检结论暂时不可用");
            return result;
        }
    }

    private QualityGateStatusEnum syncRecord(
            MesInspectionGenRecordEntity record, QmsInspectionProcedureTaskEntity qmsTask) {
        return syncRecord(record, qmsTask, GateContext.normal());
    }

    private QualityGateStatusEnum syncRecord(
            MesInspectionGenRecordEntity record,
            QmsInspectionProcedureTaskEntity qmsTask,
            GateContext context) {
        var fence = recordFence(record, true);
        if (Boolean.TRUE.equals(record.getQualityEventControlled())) {
            var item = eventProjection.item(record, qmsTask);
            org.springframework.beans.BeanUtils.copyProperties(item, record);
            if (context.persist() && Boolean.TRUE.equals(record.getQualityEventManaged())) {
                setCachedFields(fence, record);
                fence.set(MesInspectionGenRecordEntity::getGateStatus, record.getGateStatus())
                        .set(MesInspectionGenRecordEntity::getSyncMessage, record.getSyncMessage())
                        .set(
                                MesInspectionGenRecordEntity::getLastSyncTime,
                                record.getLastSyncTime());
                if (!inspectionGenRecordService.update(new MesInspectionGenRecordEntity(), fence))
                    throw new CheckedException("正式事件版本已变化或缓存未保存，请重新读取");
            }
            return QualityGateStatusEnum.valueOf(item.getGateStatus());
        }
        QualityGateStatusEnum status;
        String message;
        QmsInspectionProcedureTaskEntity verified = null;
        if (!"1".equals(record.getGenFlag()) || record.getInspectionId() == null) {
            status = QualityGateStatusEnum.ERROR;
            message = StrUtil.blankToDefault(record.getGenRemark(), "质检任务生成失败");
        } else if (qmsTask == null) {
            status = QualityGateStatusEnum.ERROR;
            message = "QMS未返回可核验的对应检验任务";
        } else {
            try {
                var proof = sourceEvidence.verify(record, qmsTask);
                verified = qmsTask;
                if (PASS_RESULTS.contains(Objects.toString(qmsTask.getResult(), ""))) {
                    status = QualityGateStatusEnum.PASSED;
                    message = "QMS来源已核验，结论已放行";
                } else if ("UN_QUALIFIED".equals(qmsTask.getResult())) {
                    status = QualityGateStatusEnum.FAILED;
                    message = "质检不合格，须完成QMS处置或返工复检";
                } else if ("CANCELED".equals(qmsTask.getResult())) {
                    status = QualityGateStatusEnum.CANCELED;
                    message = "质检任务已作废，不承载放行数量";
                } else {
                    status = QualityGateStatusEnum.PENDING;
                    message = "质检尚未形成判定";
                }
                if (!proof.rootPrimaryKeyFrozen()) message += "；原件未冻结根工单ID";
            } catch (MesQualityGateSourceEvidence.Rejected rejected) {
                status = QualityGateStatusEnum.ERROR;
                message = rejected.getMessage();
            }
        }
        boolean quantities = verified != null && status != QualityGateStatusEnum.CANCELED;
        record.setInspectionStatus(verified == null ? null : verified.getStatus());
        record.setInspectionResult(verified == null ? null : verified.getResult());
        record.setQmsTotalNum(quantities ? verified.getNum() : null);
        record.setQmsUsableNum(quantities ? verified.getUsableNum() : null);
        record.setQmsHandleNum(quantities ? verified.getHandleNum() : null);
        record.setQmsHandleResult(quantities ? verified.getHandleResult() : null);
        record.setGateStatus(status.name())
                .setSyncMessage(message)
                .setLastSyncTime(LocalDateTime.now());
        setCachedFields(fence, record);
        fence.set(MesInspectionGenRecordEntity::getGateStatus, record.getGateStatus())
                .set(MesInspectionGenRecordEntity::getSyncMessage, message)
                .set(MesInspectionGenRecordEntity::getLastSyncTime, record.getLastSyncTime());
        if (!inspectionGenRecordService.update(new MesInspectionGenRecordEntity(), fence))
            throw new CheckedException("质量门同步记录已变化或保存失败，请重新同步，不能采用过期结论");
        return status;
    }

    private LambdaQueryWrapper<MesInspectionGenRecordEntity> recordScope() {
        MesQualityGateSourceEvidence.scope();
        return new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                .eq(MesInspectionGenRecordEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(
                        MesInspectionGenRecordEntity::getFactoryCode,
                        FactoryContextHolder.getFactoryCode());
    }

    private LambdaUpdateWrapper<MesInspectionGenRecordEntity> recordFence(
            MesInspectionGenRecordEntity record) {
        return recordFence(record, false);
    }

    private LambdaUpdateWrapper<MesInspectionGenRecordEntity> recordFence(
            MesInspectionGenRecordEntity record, boolean qualityRead) {
        MesQualityGateSourceEvidence.scope();
        if (!Objects.equals(record.getTenantId(), TenantContextHolder.getTenantId())
                || !Objects.equals(record.getFactoryCode(), FactoryContextHolder.getFactoryCode()))
            throw new CheckedException("质检记录不属于当前实际组织");
        var fence =
                new LambdaUpdateWrapper<MesInspectionGenRecordEntity>()
                        .eq(MesInspectionGenRecordEntity::getId, record.getId())
                        .eq(
                                MesInspectionGenRecordEntity::getTenantId,
                                TenantContextHolder.getTenantId())
                        .eq(
                                MesInspectionGenRecordEntity::getFactoryCode,
                                FactoryContextHolder.getFactoryCode());
        matching(fence, MesInspectionGenRecordEntity::getTaskCode, record.getTaskCode());
        matching(
                fence,
                MesInspectionGenRecordEntity::getProduceTaskCode,
                record.getProduceTaskCode());
        matching(
                fence, MesInspectionGenRecordEntity::getInspectionType, record.getInspectionType());
        matching(
                fence,
                MesInspectionGenRecordEntity::getSourceRequestId,
                record.getSourceRequestId());
        matching(fence, MesInspectionGenRecordEntity::getInspectionId, record.getInspectionId());
        matching(fence, MesInspectionGenRecordEntity::getGenFlag, record.getGenFlag());
        matching(
                fence,
                MesInspectionGenRecordEntity::getQualityEventManaged,
                record.getQualityEventManaged());
        if (qualityRead) {
            if (Boolean.TRUE.equals(record.getQualityEventManaged())) {
                fence.exists(
                        "SELECT 1 FROM mom_mes.mes_inspection_event_state s WHERE s.id={0} AND"
                                + " s.tenant_id={1} AND s.factory_code={2} AND s.version={3}",
                        record.getId(),
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode(),
                        record.getQualityEventVersion());
            } else if (!Boolean.TRUE.equals(record.getQualityEventControlled())) {
                fence.notExists(
                        "SELECT 1 FROM mom_mes.mes_inspection_gen_record g WHERE g.tenant_id={0}"
                                + " AND g.factory_code={1} AND g.produce_task_code={2} AND"
                                + " g.del_flag='0' AND g.quality_event_managed=TRUE",
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode(),
                        record.getProduceTaskCode());
            }
        }
        matching(fence, MesInspectionGenRecordEntity::getLastSyncTime, record.getLastSyncTime());
        matching(fence, MesInspectionGenRecordEntity::getGateStatus, record.getGateStatus());
        matching(
                fence,
                MesInspectionGenRecordEntity::getInspectionResult,
                record.getInspectionResult());
        matching(
                fence,
                MesInspectionGenRecordEntity::getInspectionStatus,
                record.getInspectionStatus());
        matching(fence, MesInspectionGenRecordEntity::getQmsTotalNum, record.getQmsTotalNum());
        matching(fence, MesInspectionGenRecordEntity::getQmsUsableNum, record.getQmsUsableNum());
        matching(fence, MesInspectionGenRecordEntity::getQmsHandleNum, record.getQmsHandleNum());
        matching(
                fence,
                MesInspectionGenRecordEntity::getQmsHandleResult,
                record.getQmsHandleResult());
        return fence;
    }

    private void matching(
            LambdaUpdateWrapper<MesInspectionGenRecordEntity> fence,
            SFunction<MesInspectionGenRecordEntity, ?> column,
            Object value) {
        if (value == null) fence.isNull(column);
        else fence.eq(column, value);
    }

    private void setCachedFields(
            LambdaUpdateWrapper<MesInspectionGenRecordEntity> fence,
            MesInspectionGenRecordEntity record) {
        fence.set(MesInspectionGenRecordEntity::getInspectionStatus, record.getInspectionStatus())
                .set(
                        MesInspectionGenRecordEntity::getInspectionResult,
                        record.getInspectionResult())
                .set(MesInspectionGenRecordEntity::getQmsTotalNum, record.getQmsTotalNum())
                .set(MesInspectionGenRecordEntity::getQmsUsableNum, record.getQmsUsableNum())
                .set(MesInspectionGenRecordEntity::getQmsHandleNum, record.getQmsHandleNum())
                .set(MesInspectionGenRecordEntity::getQmsHandleResult, record.getQmsHandleResult());
    }

    private void registerBlock(MesInspectionGenRecordEntity record) {
        if (!inspectionGenRecordService.update(
                new LambdaUpdateWrapper<MesInspectionGenRecordEntity>()
                        .eq(
                                MesInspectionGenRecordEntity::getTenantId,
                                TenantContextHolder.getTenantId())
                        .eq(
                                MesInspectionGenRecordEntity::getFactoryCode,
                                FactoryContextHolder.getFactoryCode())
                        .eq(MesInspectionGenRecordEntity::getId, record.getId())
                        .setSql("blocked_count = COALESCE(blocked_count, 0) + 1")
                        .set(
                                MesInspectionGenRecordEntity::getLastBlockedTime,
                                LocalDateTime.now()))) throw new CheckedException("质量门阻断记录保存失败");
        record.setBlockedCount(Optional.ofNullable(record.getBlockedCount()).orElse(0) + 1);
        record.setLastBlockedTime(LocalDateTime.now());
    }

    private void ensureLastInspection(
            MesProduceTaskEntity produceTask, MesTaskEntity task, MesInspectionRuleEntity rule) {
        MesInspectionGenRecordEntity record =
                inspectionGenRecordService.getOne(
                        recordScope()
                                .eq(
                                        MesInspectionGenRecordEntity::getProduceTaskCode,
                                        produceTask.getCode())
                                .in(
                                        MesInspectionGenRecordEntity::getInspectionType,
                                        ProcedureLabelTypeEnum.LAST.name(),
                                        ProcedureLabelTypeEnum.LAST.getName())
                                .orderByDesc(MesInspectionGenRecordEntity::getId)
                                .last("LIMIT 1"),
                        false);
        if (record != null && "1".equals(record.getGenFlag()) && record.getInspectionId() != null) {
            return;
        }
        String requestId = "MES_QMS_V2:" + produceTask.getCode() + ":LAST";
        if (record == null) {
            record =
                    new MesInspectionGenRecordEntity()
                            .setTaskCode(produceTask.getTaskCode())
                            .setProduceTaskCode(produceTask.getCode())
                            .setRuleCode(rule.getCode())
                            .setRuleName(rule.getName())
                            .setInspectionType(ProcedureLabelTypeEnum.LAST.name())
                            .setSourceRequestId(requestId)
                            .setGenTime(LocalDateTime.now())
                            .setGenFlag("0")
                            .setGateStatus(QualityGateStatusEnum.ERROR.name())
                            .setBlockedCount(0)
                            .setFactoryCode(produceTask.getFactoryCode());
            record.setTenantId(TenantContextHolder.getTenantId());
            if (!inspectionGenRecordService.save(record))
                throw new CheckedException("质检生成记录保存失败，未发送创建请求");
        }
        generateInspection(record, produceTask, task, ProcedureLabelTypeEnum.LAST.name());
    }

    private void generateInspection(
            MesInspectionGenRecordEntity record,
            MesProduceTaskEntity produceTask,
            MesTaskEntity task,
            String labelType) {
        var fence = recordFence(record);
        String requestId =
                StrUtil.blankToDefault(
                        record.getSourceRequestId(),
                        "MES_QMS_V2:"
                                + produceTask.getCode()
                                + ":"
                                + labelType
                                + ":"
                                + record.getId());
        record.setSourceRequestId(requestId);
        try {
            InspectionProcedureCreateParam param =
                    sourceEvidence.replay(record, produceTask, task, requestId, labelType);
            if (param == null) {
                QmsQualityStandardEntity standard =
                        remoteQualityStandardService
                                .getRandomStandard(
                                        new QmsQualityStandardEntity()
                                                .setMatCode(produceTask.getMatCode())
                                                .setInspectionType(
                                                        InspectionTaskTypeEnum.PROCEDURE.name()))
                                .getData();
                if (standard == null) {
                    throw new IllegalStateException("未匹配到已发布的工序质检标准");
                }
                String shiftCode = remoteClassesService.getRuleBanci(LocalDateTime.now()).getData();
                String batchNo = "";
                BatchGenerator generator =
                        batchGeneratorService.getBatchGenerator(task.getBatchRuleCode());
                if (generator != null) {
                    batchNo = generator.generateBatchNumber(produceTask.getTaskCode(), shiftCode);
                }
                param = baseCreateParam(produceTask, task, standard, labelType, requestId, batchNo);
            }
            Long inspectionId =
                    qmsInspectionCoordinator.create(param, produceTask.getFactoryCode());
            record.setInspectionId(inspectionId)
                    .setGenFlag("1")
                    .setGenRemark("生成成功")
                    .setGateStatus(QualityGateStatusEnum.PENDING.name())
                    .setSyncMessage("等待" + typeLabel(labelType) + "结论");
        } catch (Exception exception) {
            log.error(
                    "创建工序质检任务失败, produceTaskCode={}, type={}",
                    produceTask.getCode(),
                    labelType,
                    exception);
            String message = rootMessage(exception);
            // 生成备注限 255 字符。远端异常摘要保存在独立同步字段和永久命令账。
            record.setGenFlag("0")
                    .setGenRemark(typeLabel(labelType) + "任务生成失败，请沿原号核对")
                    .setGateStatus(QualityGateStatusEnum.ERROR.name())
                    .setSyncMessage(message);
        }
        record.setLastSyncTime(LocalDateTime.now());
        record.setInspectionStatus(null)
                .setInspectionResult(null)
                .setQmsTotalNum(null)
                .setQmsUsableNum(null)
                .setQmsHandleNum(null)
                .setQmsHandleResult(null);
        setCachedFields(fence, record);
        if (!inspectionGenRecordService.update(record, fence))
            throw new CheckedException("质检创建回执已变化或保存失败，请沿原请求号核验");
    }

    private InspectionProcedureCreateParam baseCreateParam(
            MesProduceTaskEntity produceTask,
            MesTaskEntity task,
            QmsQualityStandardEntity standard,
            String labelType,
            String requestId,
            String batchNo) {
        InspectionProcedureCreateParam param =
                new InspectionProcedureCreateParam()
                        .setTaskCode(produceTask.getCode())
                        .setSourceTaskCode(produceTask.getCode())
                        .setSourceRequestId(requestId)
                        .setLabelType(labelType)
                        .setNum(Math.max(1, produceTask.getNoticeNum().intValue()))
                        .setBatchNo(batchNo)
                        .setMatCode(produceTask.getMatCode())
                        .setMatName(produceTask.getMatName())
                        .setMatSpec(produceTask.getMatSpec())
                        .setProcessCode(produceTask.getRouteCode())
                        .setProcessName(produceTask.getRouteName())
                        .setStandardId(standard.getId())
                        .setDeptCode(produceTask.getWorkshopCode())
                        .setOrderNo(task.getCode())
                        .setOrderId(String.valueOf(task.getId()));
        if (InspectionMethodEnum.PENDING.name().equals(standard.getInspectionMethod())) {
            String configured = remoteParamService.getByKey("QMS_DEFAULT_NUM").getData();
            if (StrUtil.isBlank(configured)) {
                throw new IllegalStateException("未配置QMS_DEFAULT_NUM抽检数量");
            }
            param.setInspectionNum(Integer.parseInt(configured));
        }
        return param;
    }

    private MesProduceTaskEntity requireProduceTask(String code) {
        return sourceEvidence.parent(code);
    }

    private MesInspectionRuleEntity configuredRule(MesProduceTaskEntity process) {
        MesQualityGateSourceEvidence.scope();
        var rule = inspectionRuleService.getRuleByMatCode(process.getMatCode());
        if (rule == null
                || !Objects.equals(rule.getTenantId(), TenantContextHolder.getTenantId())
                || !Objects.equals(rule.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                || !"1".equals(rule.getStatus())
                || StrUtil.isBlank(rule.getCode())
                || StrUtil.isBlank(rule.getGateMode()))
            throw new CheckedException("需检工序缺少当前实际组织的明确启用规则，请核对配置");
        if (gateMode(rule) != QualityGateModeEnum.NONE) {
            var types = inspectionTypes(rule);
            if (types.isEmpty() || !Set.of("FIRST", "MIDDLE", "LAST").containsAll(types))
                throw new CheckedException("需检工序缺少明确的合法检验阶段，不能按无门控处理");
        }
        return rule;
    }

    private QualityGateModeEnum gateMode(MesInspectionRuleEntity rule) {
        return rule == null
                ? QualityGateModeEnum.NONE
                : QualityGateModeEnum.fromCode(rule.getGateMode());
    }

    private boolean containsType(MesInspectionRuleEntity rule, ProcedureLabelTypeEnum type) {
        return inspectionTypes(rule).contains(type.name());
    }

    private Set<String> inspectionTypes(MesInspectionRuleEntity rule) {
        if (rule == null || StrUtil.isBlank(rule.getInspectionTypes())) {
            return Set.of();
        }
        return Arrays.stream(rule.getInspectionTypes().split(","))
                .map(this::normalizeType)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalizeType(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        for (ProcedureLabelTypeEnum type : ProcedureLabelTypeEnum.values()) {
            if (type.name().equalsIgnoreCase(value.trim()) || type.getName().equals(value.trim())) {
                return type.name();
            }
        }
        return value.trim().toUpperCase();
    }

    private String typeLabel(String type) {
        String normalized = normalizeType(type);
        try {
            return ProcedureLabelTypeEnum.valueOf(normalized).getName();
        } catch (IllegalArgumentException exception) {
            return StrUtil.blankToDefault(type, "未知检验");
        }
    }

    private String statusMessage(
            MesInspectionGenRecordEntity record, QualityGateStatusEnum status) {
        return switch (status) {
            case PENDING -> "待检";
            case FAILED -> "不合格";
            case CANCELED -> "已作废";
            case ERROR -> "异常：" + StrUtil.blankToDefault(record.getSyncMessage(), record.getGenRemark());
            case PASSED -> "已放行";
        };
    }

    private MesQualityGateItemDTO toItem(MesInspectionGenRecordEntity record) {
        return new MesQualityGateItemDTO()
                .setRecordId(record.getId())
                .setTaskCode(record.getTaskCode())
                .setProduceTaskCode(record.getProduceTaskCode())
                .setInspectionType(normalizeType(record.getInspectionType()))
                .setInspectionId(record.getInspectionId())
                .setSourceRequestId(record.getSourceRequestId())
                .setGenFlag(record.getGenFlag())
                .setGenRemark(record.getGenRemark())
                .setInspectionStatus(record.getInspectionStatus())
                .setInspectionResult(record.getInspectionResult())
                .setQmsTotalNum(record.getQmsTotalNum())
                .setQmsUsableNum(record.getQmsUsableNum())
                .setQmsHandleNum(record.getQmsHandleNum())
                .setQmsHandleResult(record.getQmsHandleResult())
                .setGateStatus(record.getGateStatus())
                .setSyncMessage(record.getSyncMessage())
                .setBlockedCount(record.getBlockedCount())
                .setGenTime(record.getGenTime())
                .setLastSyncTime(record.getLastSyncTime())
                .setLastBlockedTime(record.getLastBlockedTime())
                .setQualityEventId(record.getQualityEventId())
                .setQualityEventSequence(record.getQualityEventSequence())
                .setQualityEventFingerprint(record.getQualityEventFingerprint())
                .setQualityEventVersion(record.getQualityEventVersion())
                .setQualityEventState(record.getQualityEventState())
                .setQualityBlockedEventId(record.getQualityBlockedEventId());
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message =
                StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
        return message.length() > 800 ? message.substring(0, 800) : message;
    }

    private record GateContext(
            Long recordId, Long eventId, Date time, boolean persist, boolean localOnly) {
        static GateContext normal() {
            return new GateContext(null, null, null, true, false);
        }
    }
}
