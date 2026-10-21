package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mes.batch.BatchGenerator;
import com.kitex.mom.mes.batch.BatchGeneratorService;
import com.kitex.mom.mes.dto.MesQualityGateDecision;
import com.kitex.mom.mes.dto.MesQualityGateItemDTO;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.entity.MesInspectionRuleEntity;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.QualityGateModeEnum;
import com.kitex.mom.mes.enums.QualityGateStatusEnum;
import com.kitex.mom.mes.mapper.MesChangePointHandleMapper;
import com.kitex.mom.mes.service.*;
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

    private static final Set<String> PASS_RESULTS = Set.of(
            InspectionTaskResultEnum.QUALIFIED.name(), InspectionTaskResultEnum.EXEMPTION.name(),
            InspectionTaskResultEnum.RELEASE.name(), InspectionTaskResultEnum.RECEIVE.name());

    private final MesProduceTaskService produceTaskService;
    private final MesInspectionRuleService inspectionRuleService;
    private final MesInspectionGenRecordService inspectionGenRecordService;
    private final RemoteProcedureService remoteProcedureService;
    private final MesQmsInspectionCoordinator qmsInspectionCoordinator;
    private final RemoteQualityStandardService remoteQualityStandardService;
    private final RemoteClassesService remoteClassesService;
    private final RemoteParamService remoteParamService;
    private final BatchGeneratorService batchGeneratorService;
    private final MesTaskService mesTaskService;
    private final MesChangePointHandleMapper changePointHandleMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision checkReport(String produceTaskCode) {
        MesProduceTaskEntity produceTask = requireProduceTask(produceTaskCode);
        MesQualityGateDecision standard = evaluate(produceTask, "REPORT", Set.of(
                ProcedureLabelTypeEnum.FIRST.name(), ProcedureLabelTypeEnum.MIDDLE.name()), true);
        return mergeIntensifiedGate(standard, evaluateIntensifiedReportGate(produceTask));
    }

    private MesQualityGateDecision mergeIntensifiedGate(MesQualityGateDecision standard,
                                                         MesQualityGateDecision intensified) {
        if (intensified.getItems().isEmpty() && Boolean.TRUE.equals(intensified.getAllowed())) {
            return standard;
        }
        standard.getItems().addAll(intensified.getItems());
        standard.setAllowed(Boolean.TRUE.equals(standard.getAllowed()) && Boolean.TRUE.equals(intensified.getAllowed()))
                .setPassed(Boolean.TRUE.equals(standard.getPassed()) && Boolean.TRUE.equals(intensified.getPassed()))
                .setGateMode(Boolean.TRUE.equals(intensified.getAllowed())
                        ? standard.getGateMode() : QualityGateModeEnum.BLOCK.name())
                .setMessage(Boolean.TRUE.equals(intensified.getAllowed()) ? standard.getMessage()
                        : standard.getMessage() + "；" + intensified.getMessage());
        return standard;
    }

    /**
     * 变化点加严巡检是独立生产控制，不依赖物料普通质检规则是否配置了中检。
     */
    private MesQualityGateDecision evaluateIntensifiedReportGate(MesProduceTaskEntity produceTask) {
        MesQualityGateDecision decision = new MesQualityGateDecision().setGateMode(QualityGateModeEnum.BLOCK.name())
                .setPhase("CHANGE_POINT_MONITOR").setAllowed(true).setPassed(true)
                .setMessage("当前无到期的变化点加严巡检");
        List<MesChangePointHandleEntity> monitoring = changePointHandleMapper.selectList(
                new LambdaQueryWrapper<MesChangePointHandleEntity>()
                        .eq(MesChangePointHandleEntity::getStatus, "M")
                        .eq(MesChangePointHandleEntity::getProduceTaskCode, produceTask.getCode())
                        .eq(MesChangePointHandleEntity::getFactoryCode, produceTask.getFactoryCode()));
        if (monitoring.isEmpty()) return decision;

        List<String> problems = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        List<Long> recordIds = monitoring.stream()
                .map(MesChangePointHandleEntity::getIntensifiedInspectionRecordId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, MesInspectionGenRecordEntity> records = recordIds.isEmpty() ? Map.of()
                : inspectionGenRecordService.listByIds(recordIds).stream().collect(Collectors.toMap(
                        MesInspectionGenRecordEntity::getId, Function.identity(), (left, right) -> left));
        List<MesInspectionGenRecordEntity> involved = new ArrayList<>();
        for (MesChangePointHandleEntity event : monitoring) {
            if (event.getIntensifiedInspectionRecordId() == null) {
                if (event.getIntensifiedInspectionNextTime() == null
                        || !event.getIntensifiedInspectionNextTime().isAfter(now)) {
                    problems.add("变化点[" + event.getEventCode() + "]加严巡检已到期但尚未生成");
                }
                continue;
            }
            MesInspectionGenRecordEntity record = records.get(event.getIntensifiedInspectionRecordId());
            if (record == null || !Objects.equals(record.getProduceTaskCode(), produceTask.getCode())
                    || !Objects.equals(record.getFactoryCode(), produceTask.getFactoryCode())) {
                problems.add("变化点[" + event.getEventCode() + "]加严巡检来源不存在或归属不一致");
                continue;
            }
            involved.add(record);
        }
        Map<Long, QmsInspectionProcedureTaskEntity> qmsTasks = loadQmsTasks(involved, problems);
        for (MesInspectionGenRecordEntity record : involved) {
            QualityGateStatusEnum status = syncRecord(record,
                    record.getInspectionId() == null ? null : qmsTasks.get(record.getInspectionId()));
            decision.getItems().add(toItem(record));
            if (status != QualityGateStatusEnum.PASSED) {
                problems.add("加严巡检[" + Optional.ofNullable(record.getInspectionId())
                        .map(String::valueOf).orElse("未生成") + "]" + statusMessage(record, status));
                registerBlock(record);
            }
        }
        if (problems.isEmpty()) {
            return decision.setMessage("当前变化点加严巡检已放行");
        }
        return decision.setAllowed(false).setPassed(false).setMessage(String.join("；", problems));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision checkFinish(MesProduceTaskEntity produceTask, MesTaskEntity task) {
        MesQualityGateDecision standard;
        if (!"1".equals(produceTask.getIsInspection())) {
            standard = new MesQualityGateDecision().setAllowed(true).setGateMode(QualityGateModeEnum.NONE.name())
                    .setPassed(true).setPhase("FINISH").setMessage("当前工序不需要质检");
        } else {
            MesInspectionRuleEntity rule = inspectionRuleService.getRuleByMatCode(produceTask.getMatCode());
            QualityGateModeEnum mode = gateMode(rule);
            if (mode != QualityGateModeEnum.NONE && containsType(rule, ProcedureLabelTypeEnum.LAST)) {
                ensureLastInspection(produceTask, task, rule);
            }
            // 完工既要满足末检，也不能绕过此前仍未放行的首检/中检。
            standard = evaluate(produceTask, "FINISH", Set.of(ProcedureLabelTypeEnum.FIRST.name(),
                    ProcedureLabelTypeEnum.MIDDLE.name(), ProcedureLabelTypeEnum.LAST.name()), true);
        }
        // 变化点加严巡检是独立生产控制，即使普通质检开关关闭也不能通过直接完工绕过。
        return mergeIntensifiedGate(standard, evaluateIntensifiedReportGate(produceTask));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision sync(String produceTaskCode) {
        MesProduceTaskEntity produceTask = requireProduceTask(produceTaskCode);
        return evaluate(produceTask, "ALL", Set.of(ProcedureLabelTypeEnum.FIRST.name(),
                ProcedureLabelTypeEnum.MIDDLE.name(), ProcedureLabelTypeEnum.LAST.name()), false);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision retryGeneration(Long recordId) {
        MesInspectionGenRecordEntity record = inspectionGenRecordService.getById(recordId);
        if (record == null) {
            throw new IllegalArgumentException("质检生成记录不存在");
        }
        if ("1".equals(record.getGenFlag()) && record.getInspectionId() != null) {
            throw new IllegalArgumentException("质检任务已生成，只需同步结论，无需重试生成");
        }
        MesProduceTaskEntity produceTask = requireProduceTask(record.getProduceTaskCode());
        MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, produceTask.getTaskCode()));
        if (task == null) {
            throw new IllegalArgumentException("未找到质检记录对应的生产工单");
        }
        String type = normalizeType(record.getInspectionType());
        if (!Set.of(ProcedureLabelTypeEnum.FIRST.name(), ProcedureLabelTypeEnum.MIDDLE.name(),
                ProcedureLabelTypeEnum.LAST.name()).contains(type)) {
            throw new IllegalArgumentException("不支持重试的质检类型：" + record.getInspectionType());
        }
        generateInspection(record, produceTask, task, type);
        return evaluate(produceTask, "ALL", Set.of(ProcedureLabelTypeEnum.FIRST.name(),
                ProcedureLabelTypeEnum.MIDDLE.name(), ProcedureLabelTypeEnum.LAST.name()), false);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision createChangePointFirstInspection(String produceTaskCode, String sourceRequestId,
                                                                   String ruleCode, String ruleName) {
        return createChangePointInspection(produceTaskCode, sourceRequestId, ruleCode, ruleName,
                ProcedureLabelTypeEnum.FIRST.name());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision createChangePointIntensifiedInspection(String produceTaskCode,
                                                                          String sourceRequestId,
                                                                          String ruleCode, String ruleName) {
        return createChangePointInspection(produceTaskCode, sourceRequestId, ruleCode, ruleName,
                ProcedureLabelTypeEnum.MIDDLE.name());
    }

    private MesQualityGateDecision createChangePointInspection(String produceTaskCode, String sourceRequestId,
                                                                String ruleCode, String ruleName,
                                                                String inspectionType) {
        if (StrUtil.isBlank(sourceRequestId)) {
            throw new IllegalArgumentException("变化点质检来源请求号不能为空");
        }
        MesProduceTaskEntity produceTask = requireProduceTask(produceTaskCode);
        MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, produceTask.getTaskCode()));
        if (task == null) {
            throw new IllegalArgumentException("未找到变化点质检对应的生产工单");
        }
        MesInspectionGenRecordEntity record = inspectionGenRecordService.getOne(
                new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                        .eq(MesInspectionGenRecordEntity::getSourceRequestId, sourceRequestId)
                        .orderByDesc(MesInspectionGenRecordEntity::getId).last("LIMIT 1"), false);
        if (record == null) {
            record = new MesInspectionGenRecordEntity()
                    .setTaskCode(produceTask.getTaskCode()).setProduceTaskCode(produceTask.getCode())
                    .setRuleCode(ruleCode).setRuleName(ruleName)
                    .setInspectionType(inspectionType)
                    .setSourceRequestId(sourceRequestId).setGenTime(LocalDateTime.now())
                    .setGenFlag("0").setGateStatus(QualityGateStatusEnum.ERROR.name())
                    .setBlockedCount(0).setFactoryCode(produceTask.getFactoryCode());
            inspectionGenRecordService.save(record);
        }
        if (!"1".equals(record.getGenFlag()) || record.getInspectionId() == null) {
            generateInspection(record, produceTask, task, inspectionType);
        }
        return syncChangePointInspection(record.getId());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision syncChangePointInspection(Long recordId) {
        MesInspectionGenRecordEntity record = inspectionGenRecordService.getById(recordId);
        if (record == null) {
            return new MesQualityGateDecision().setAllowed(false).setPassed(false).setGateMode("BLOCK")
                    .setPhase("CHANGE_POINT").setMessage("变化点质检记录不存在");
        }
        List<String> problems = new ArrayList<>();
        Map<Long, QmsInspectionProcedureTaskEntity> tasks = loadQmsTasks(List.of(record), problems);
        QualityGateStatusEnum status = syncRecord(record,
                record.getInspectionId() == null ? null : tasks.get(record.getInspectionId()));
        boolean passed = status == QualityGateStatusEnum.PASSED && problems.isEmpty();
        String inspectionLabel = typeLabel(record.getInspectionType());
        String message = passed ? "变化点" + inspectionLabel + "已放行" : (problems.isEmpty()
                ? "变化点" + inspectionLabel + statusMessage(record, status) : String.join("；", problems));
        return new MesQualityGateDecision().setAllowed(passed).setPassed(passed).setGateMode("BLOCK")
                .setPhase("CHANGE_POINT").setMessage(message).setItems(List.of(toItem(record)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MesQualityGateDecision retryChangePointInspection(Long recordId) {
        MesInspectionGenRecordEntity record = inspectionGenRecordService.getById(recordId);
        if (record == null) {
            throw new IllegalArgumentException("变化点质检记录不存在");
        }
        if ("1".equals(record.getGenFlag()) && record.getInspectionId() != null) {
            return syncChangePointInspection(recordId);
        }
        MesProduceTaskEntity produceTask = requireProduceTask(record.getProduceTaskCode());
        MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, produceTask.getTaskCode()));
        if (task == null) {
            throw new IllegalArgumentException("未找到变化点质检对应的生产工单");
        }
        generateInspection(record, produceTask, task, ProcedureLabelTypeEnum.FIRST.name());
        return syncChangePointInspection(recordId);
    }

    private MesQualityGateDecision evaluate(MesProduceTaskEntity produceTask, String phase,
                                            Set<String> phaseTypes, boolean registerBlock) {
        MesInspectionRuleEntity rule = inspectionRuleService.getRuleByMatCode(produceTask.getMatCode());
        QualityGateModeEnum mode = gateMode(rule);
        MesQualityGateDecision decision = new MesQualityGateDecision().setGateMode(mode.name()).setPhase(phase);
        if (!"1".equals(produceTask.getIsInspection())) {
            return decision.setAllowed(true).setGateMode(QualityGateModeEnum.NONE.name())
                    .setPassed(true).setMessage("当前工序不需要质检");
        }
        if (mode == QualityGateModeEnum.NONE || rule == null) {
            return decision.setAllowed(true).setPassed(true).setMessage("质量门未启用");
        }
        Set<String> configuredTypes = inspectionTypes(rule);
        Set<String> requiredTypes = phaseTypes.stream().filter(configuredTypes::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requiredTypes.isEmpty()) {
            return decision.setAllowed(true).setPassed(true).setMessage("当前阶段未配置质量门");
        }

        List<MesInspectionGenRecordEntity> records = inspectionGenRecordService.list(
                new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                        .eq(MesInspectionGenRecordEntity::getProduceTaskCode, produceTask.getCode())
                        .orderByAsc(MesInspectionGenRecordEntity::getGenTime)
                        .orderByAsc(MesInspectionGenRecordEntity::getId));
        List<MesInspectionGenRecordEntity> involved = records.stream()
                .filter(record -> requiredTypes.contains(normalizeType(record.getInspectionType())))
                .toList();
        List<String> problems = new ArrayList<>();
        if (requiredTypes.contains(ProcedureLabelTypeEnum.FIRST.name())
                && involved.stream().noneMatch(record -> ProcedureLabelTypeEnum.FIRST.name()
                .equals(normalizeType(record.getInspectionType())))) {
            problems.add("首检任务未生成");
        }
        if ("FINISH".equals(phase) && requiredTypes.contains(ProcedureLabelTypeEnum.LAST.name())
                && involved.stream().noneMatch(record -> ProcedureLabelTypeEnum.LAST.name()
                .equals(normalizeType(record.getInspectionType())))) {
            problems.add("末检任务未生成");
        }

        Map<Long, QmsInspectionProcedureTaskEntity> qmsTasks = loadQmsTasks(involved, problems);
        for (MesInspectionGenRecordEntity record : involved) {
            QmsInspectionProcedureTaskEntity qmsTask = record.getInspectionId() == null
                    ? null : qmsTasks.get(record.getInspectionId());
            QualityGateStatusEnum gateStatus = syncRecord(record, qmsTask);
            if (gateStatus != QualityGateStatusEnum.PASSED) {
                problems.add(typeLabel(record.getInspectionType()) + "[" +
                        Optional.ofNullable(record.getInspectionId()).map(String::valueOf).orElse("未生成") + "]" +
                        statusMessage(record, gateStatus));
                if (registerBlock && mode == QualityGateModeEnum.BLOCK) {
                    registerBlock(record);
                }
            }
            decision.getItems().add(toItem(record));
        }
        boolean passed = problems.isEmpty();
        boolean allowed = passed || mode == QualityGateModeEnum.WARN;
        String message = passed ? "质量门已放行" : String.join("；", problems);
        if (!passed && mode == QualityGateModeEnum.WARN) {
            message = "质量门预警（不阻断）：" + message;
        }
        return decision.setAllowed(allowed).setPassed(passed).setMessage(message);
    }

    private Map<Long, QmsInspectionProcedureTaskEntity> loadQmsTasks(
            List<MesInspectionGenRecordEntity> records, List<String> problems) {
        List<Long> ids = records.stream().filter(record -> "1".equals(record.getGenFlag()))
                .map(MesInspectionGenRecordEntity::getInspectionId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            R<List<QmsInspectionProcedureTaskEntity>> response = remoteProcedureService.getTaskByIds(ids);
            List<QmsInspectionProcedureTaskEntity> tasks = response == null || response.getData() == null
                    ? List.of() : response.getData();
            return tasks.stream().collect(Collectors.toMap(QmsInspectionProcedureTaskEntity::getId,
                    Function.identity(), (left, right) -> left));
        } catch (Exception exception) {
            log.error("同步QMS工序质检任务失败, ids={}", ids, exception);
            problems.add("QMS质检结论暂时不可用");
            return Map.of();
        }
    }

    private QualityGateStatusEnum syncRecord(MesInspectionGenRecordEntity record,
                                             QmsInspectionProcedureTaskEntity qmsTask) {
        QualityGateStatusEnum status;
        String message;
        if (!"1".equals(record.getGenFlag()) || record.getInspectionId() == null) {
            status = QualityGateStatusEnum.ERROR;
            message = StrUtil.blankToDefault(record.getGenRemark(), "质检任务生成失败");
        } else if (qmsTask == null) {
            status = QualityGateStatusEnum.ERROR;
            message = "QMS未返回对应质检任务";
        } else if (PASS_RESULTS.contains(qmsTask.getResult())) {
            status = QualityGateStatusEnum.PASSED;
            message = "QMS结论已放行";
        } else if (InspectionTaskResultEnum.UN_QUALIFIED.name().equals(qmsTask.getResult())) {
            status = QualityGateStatusEnum.FAILED;
            message = "质检不合格，需在QMS完成处置或返工复检";
        } else if (InspectionTaskResultEnum.CANCELED.name().equals(qmsTask.getResult())) {
            status = QualityGateStatusEnum.CANCELED;
            message = "质检任务已作废，禁止作为放行证据";
        } else {
            status = QualityGateStatusEnum.PENDING;
            message = "质检尚未形成可放行结论";
        }
        record.setInspectionStatus(qmsTask == null ? null : qmsTask.getStatus());
        record.setInspectionResult(qmsTask == null ? null : qmsTask.getResult());
        record.setQmsTotalNum(qmsTask == null ? null : qmsTask.getNum());
        record.setQmsUsableNum(qmsTask == null ? null : qmsTask.getUsableNum());
        record.setQmsHandleNum(qmsTask == null ? null : qmsTask.getHandleNum());
        record.setQmsHandleResult(qmsTask == null ? null : qmsTask.getHandleResult());
        record.setGateStatus(status.name());
        record.setSyncMessage(message);
        record.setLastSyncTime(LocalDateTime.now());
        inspectionGenRecordService.updateById(record);
        return status;
    }

    private void registerBlock(MesInspectionGenRecordEntity record) {
        inspectionGenRecordService.update(new LambdaUpdateWrapper<MesInspectionGenRecordEntity>()
                .eq(MesInspectionGenRecordEntity::getId, record.getId())
                .setSql("blocked_count = COALESCE(blocked_count, 0) + 1")
                .set(MesInspectionGenRecordEntity::getLastBlockedTime, LocalDateTime.now()));
        record.setBlockedCount(Optional.ofNullable(record.getBlockedCount()).orElse(0) + 1);
        record.setLastBlockedTime(LocalDateTime.now());
    }

    private void ensureLastInspection(MesProduceTaskEntity produceTask, MesTaskEntity task,
                                      MesInspectionRuleEntity rule) {
        MesInspectionGenRecordEntity record = inspectionGenRecordService.getOne(
                new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                        .eq(MesInspectionGenRecordEntity::getProduceTaskCode, produceTask.getCode())
                        .in(MesInspectionGenRecordEntity::getInspectionType,
                                ProcedureLabelTypeEnum.LAST.name(), ProcedureLabelTypeEnum.LAST.getName())
                        .orderByDesc(MesInspectionGenRecordEntity::getId).last("LIMIT 1"), false);
        if (record != null && "1".equals(record.getGenFlag()) && record.getInspectionId() != null) {
            return;
        }
        String requestId = "MES_QMS_V2:" + produceTask.getCode() + ":LAST";
        if (record == null) {
            record = new MesInspectionGenRecordEntity()
                    .setTaskCode(produceTask.getTaskCode()).setProduceTaskCode(produceTask.getCode())
                    .setRuleCode(rule.getCode()).setRuleName(rule.getName())
                    .setInspectionType(ProcedureLabelTypeEnum.LAST.name())
                    .setSourceRequestId(requestId).setGenTime(LocalDateTime.now())
                    .setGenFlag("0").setGateStatus(QualityGateStatusEnum.ERROR.name())
                    .setBlockedCount(0).setFactoryCode(produceTask.getFactoryCode());
            inspectionGenRecordService.save(record);
        }
        generateInspection(record, produceTask, task, ProcedureLabelTypeEnum.LAST.name());
    }

    private void generateInspection(MesInspectionGenRecordEntity record, MesProduceTaskEntity produceTask,
                                    MesTaskEntity task, String labelType) {
        String requestId = StrUtil.blankToDefault(record.getSourceRequestId(),
                "MES_QMS_V2:" + produceTask.getCode() + ":" + labelType + ":" + record.getId());
        record.setSourceRequestId(requestId);
        try {
            QmsQualityStandardEntity standard = remoteQualityStandardService.getRandomStandard(
                    new QmsQualityStandardEntity().setMatCode(produceTask.getMatCode())
                            .setInspectionType(InspectionTaskTypeEnum.PROCEDURE.name())).getData();
            if (standard == null) {
                throw new IllegalStateException("未匹配到已发布的工序质检标准");
            }
            String shiftCode = remoteClassesService.getRuleBanci(LocalDateTime.now()).getData();
            String batchNo = "";
            BatchGenerator generator = batchGeneratorService.getBatchGenerator(task.getBatchRuleCode());
            if (generator != null) {
                batchNo = generator.generateBatchNumber(produceTask.getTaskCode(), shiftCode);
            }
            InspectionProcedureCreateParam param = baseCreateParam(produceTask, task,
                    standard, labelType, requestId, batchNo);
            Long inspectionId = qmsInspectionCoordinator.create(param, produceTask.getFactoryCode());
            record.setInspectionId(inspectionId).setGenFlag("1").setGenRemark("生成成功")
                    .setGateStatus(QualityGateStatusEnum.PENDING.name())
                    .setSyncMessage("等待" + typeLabel(labelType) + "结论");
        } catch (Exception exception) {
            log.error("创建工序质检任务失败, produceTaskCode={}, type={}", produceTask.getCode(), labelType, exception);
            String message = rootMessage(exception);
            record.setGenFlag("0").setGenRemark(typeLabel(labelType) + "任务生成失败：" + message)
                    .setGateStatus(QualityGateStatusEnum.ERROR.name()).setSyncMessage(message);
        }
        record.setLastSyncTime(LocalDateTime.now());
        inspectionGenRecordService.updateById(record);
    }

    private InspectionProcedureCreateParam baseCreateParam(MesProduceTaskEntity produceTask, MesTaskEntity task,
                                                           QmsQualityStandardEntity standard, String labelType,
                                                           String requestId, String batchNo) {
        InspectionProcedureCreateParam param = new InspectionProcedureCreateParam()
                .setTaskCode(produceTask.getCode()).setSourceTaskCode(produceTask.getCode())
                .setSourceRequestId(requestId).setLabelType(labelType)
                .setNum(Math.max(1, produceTask.getNoticeNum().intValue()))
                .setBatchNo(batchNo).setMatCode(produceTask.getMatCode()).setMatName(produceTask.getMatName())
                .setMatSpec(produceTask.getMatSpec()).setProcessCode(produceTask.getRouteCode())
                .setProcessName(produceTask.getRouteName()).setStandardId(standard.getId())
                .setDeptCode(produceTask.getWorkshopCode()).setOrderNo(task.getCode())
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
        MesProduceTaskEntity task = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getCode, code));
        if (task == null) {
            throw new IllegalArgumentException("未找到工序任务：" + code);
        }
        return task;
    }

    private QualityGateModeEnum gateMode(MesInspectionRuleEntity rule) {
        return rule == null ? QualityGateModeEnum.NONE : QualityGateModeEnum.fromCode(rule.getGateMode());
    }

    private boolean containsType(MesInspectionRuleEntity rule, ProcedureLabelTypeEnum type) {
        return inspectionTypes(rule).contains(type.name());
    }

    private Set<String> inspectionTypes(MesInspectionRuleEntity rule) {
        if (rule == null || StrUtil.isBlank(rule.getInspectionTypes())) {
            return Set.of();
        }
        return Arrays.stream(rule.getInspectionTypes().split(",")).map(this::normalizeType)
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
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

    private String statusMessage(MesInspectionGenRecordEntity record, QualityGateStatusEnum status) {
        return switch (status) {
            case PENDING -> "待检";
            case FAILED -> "不合格";
            case CANCELED -> "已作废";
            case ERROR -> "异常：" + StrUtil.blankToDefault(record.getSyncMessage(), record.getGenRemark());
            case PASSED -> "已放行";
        };
    }

    private MesQualityGateItemDTO toItem(MesInspectionGenRecordEntity record) {
        return new MesQualityGateItemDTO().setRecordId(record.getId()).setTaskCode(record.getTaskCode())
                .setProduceTaskCode(record.getProduceTaskCode()).setInspectionType(normalizeType(record.getInspectionType()))
                .setInspectionId(record.getInspectionId()).setSourceRequestId(record.getSourceRequestId())
                .setGenFlag(record.getGenFlag()).setGenRemark(record.getGenRemark())
                .setInspectionStatus(record.getInspectionStatus()).setInspectionResult(record.getInspectionResult())
                .setQmsTotalNum(record.getQmsTotalNum()).setQmsUsableNum(record.getQmsUsableNum())
                .setQmsHandleNum(record.getQmsHandleNum()).setQmsHandleResult(record.getQmsHandleResult())
                .setGateStatus(record.getGateStatus()).setSyncMessage(record.getSyncMessage())
                .setBlockedCount(record.getBlockedCount()).setGenTime(record.getGenTime())
                .setLastSyncTime(record.getLastSyncTime()).setLastBlockedTime(record.getLastBlockedTime());
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
        return message.length() > 800 ? message.substring(0, 800) : message;
    }
}
