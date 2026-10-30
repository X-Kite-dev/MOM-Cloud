package com.kitex.mom.mes.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.*;
import com.kitex.mom.mes.event.FeedRecordSaveEvent;
import com.kitex.mom.mes.extension.MesReportExtensionChain;
import com.kitex.mom.mes.extension.MesReportExtensionContext;
import com.kitex.mom.mes.mapper.MesReportReverseRequestMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.process.ProcessOperationFactory;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.mes.support.MesQualityEvidenceSupport;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 生产报工
 *
 * @author kitex
 * @since 2025-05-06
 */
@Service
@AllArgsConstructor
public class MesTaskReportServiceImpl extends ServiceImpl<MesTaskReportMapper, MesTaskReportEntity> implements MesTaskReportService {

    private final MesTaskReportRecordService recordService;
    private final MesTaskReportUnqualifiedService unqualifiedService;
    private final MesTaskReportFeedService feedReportService;
    private final MesTaskArrangeService arrangeService;
    private final RemoteMaterialService matService;
    private final MesTaskService taskService;
    private final MesReworkMaterialPlanService reworkMaterialPlans;
    private final MesProduceTaskService produceTaskService;
    private final ProcessOperationFactory processOperationFactory;
    private final MesQualityGateService qualityGateService;
    private final MesQualityQuantityService qualityQuantityService;
    private final MesDeviceGateService deviceGateService;
    private final MesChangePointHandleService changePointHandleService;
    private final MesAndonProductionReleaseService andonProductionReleaseService;
    private final MesRepairProductionReleaseService repairProductionReleaseService;
    private final MesApsSourceQuantityService sourceQuantityService;
    private final MesStationPassService stationPassService;
    private final MesExecutionTemplateService executionTemplateService;
    private final MesIdentifierMappingService identifierMappingService;
    private final MesIdentifierRouteReturnCoordinator routeReturnCoordinator;
    private final MesReportExtensionChain reportExtensionChain;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesReportReverseRequestMapper reverseRequestMapper;
    private final MesReportCommandService reportCommands;
    private final MesExecutionTaskLock taskLock;
    private final MesIdentifierBindingService identifierBindings;
    private final MesReworkIdentifierUsageService reworkIdentifiers;
    private final MesTerminalExecutionGuard terminalExecutionGuard;
    private final MesProductionMaterialOperationService productionMaterials;
    private final MesStartMaterialBindingReader materialBindings;
    private final MesProcessTimingGuard processTimingGuard;
    private final MesErpPostingOutboxService erpPostingOutbox;


    /**
     * 生产报工
     *
     * @param params
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void report(MesTaskReportVO params) {
        reportInternal(params, MesReportChannelEnum.STATION);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void reportMobile(MesTaskReportVO params) {
        reportInternal(params, MesReportChannelEnum.MOBILE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void reportIntegration(MesTaskReportVO params) {
        reportInternal(params, MesReportChannelEnum.INTEGRATION);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void reportAuto(MesTaskReportVO params) {
        reportInternal(params, MesReportChannelEnum.DEVICE);
    }

    private void reportInternal(MesTaskReportVO params, MesReportChannelEnum channel) {
        boolean automatic = channel.isAutomatic();
        if (params == null || StrUtil.isBlank(params.getArrangeCode())) {
            throw new CheckedException("派工单号不能为空");
        }
        String factory = factoryAccess.require("write");
        prepareRequestId(params);
        // 原正文先于当前条码解析及可变门状态冻结；回放原号不再次记产、占码或扣料。
        MesReportCommandService.CapturedCommand captured = reportCommands.capture(params, channel);
        if (captured.replayReportId() != null) return;
        MesTaskArrangeEntity ownership = taskLock.arrange(params.getArrangeCode());
        MesTaskArrangeEntity arrange = arrangeService.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, params.getArrangeCode()).last("FOR UPDATE"));

        if (Objects.isNull(arrange)) {
            throw new CheckedException("未找到派工任务");
        }
        taskLock.assertArrange(ownership, arrange);
        terminalExecutionGuard.assertUnoccupied(arrange);
        MesTaskEntity task = taskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("FOR UPDATE"));
        if (Objects.isNull(task)) {
            throw new CheckedException("未找到派工任务对应的生产工单");
        }

        if (automatic) {
            if (StrUtil.isBlank(params.getSourceEventId()) || StrUtil.isBlank(params.getDeviceCode())) {
                throw new CheckedException("设备自动报工必须提供来源事件号和设备编码");
            }
            MesTaskReportEntity sourceOwner = this.getOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                    .eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesTaskReportEntity::getFactoryCode, factory)
                    .eq(MesTaskReportEntity::getSourceEventId, params.getSourceEventId()), false);
            if (sourceOwner != null && !Objects.equals(sourceOwner.getRequestId(), params.getRequestId())) {
                throw new CheckedException("设备来源事件已生成其他报工记录，禁止重复记产");
            }
        }
        ReportModeEnum reportMode = ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType());
        MesReworkIdentifierUsageService.Prepared reworkIdentifier = reworkIdentifiers.prepare(
                params, arrange, task, reportMode, "GOOD");
        MesIdentifierBindingService.Prepared binding = reworkIdentifier == null
                ? identifierBindings.prepare(params, arrange, task, reportMode, captured) : null;
        routeReturnCoordinator.prepareExecution(params, arrange, task, reportMode);
        if (binding != null && (!MesIdentifierRouteReturnCoordinator.NORMAL.equals(params.getExecutionKind())
                || !Integer.valueOf(1).equals(params.getIdentifierRouteVersion()))) {
            throw new CheckedException("动态区段不能沿旧退回路线重建占用，必须使用原正常工艺节点");
        }
        boolean routeReturn = MesIdentifierRouteReturnCoordinator.ROUTE_RETURN.equals(params.getExecutionKind());
        if ((!routeReturn && !MesProduceTaskStatusEnum.PRODUCT.name().equals(arrange.getStatus()))
                || (routeReturn && !java.util.Set.of(MesProduceTaskStatusEnum.PRODUCT.name(),
                MesProduceTaskStatusEnum.FINISH.name()).contains(arrange.getStatus()))) {
            throw new CheckedException(routeReturn
                    ? "退回路线仅允许使用生产中或已完成的原派工任务，暂停/关闭任务须先受控恢复"
                    : "生产任务未在进行中，请先对任务开工");
        }
        MesProduceTaskEntity reportParent = requireReportParents(arrange, task, routeReturn);
        executionTemplateService.validateReport(arrange, params, channel);
        reworkMaterialPlans.assertReady(task);
        prepareReportContext(params, arrange, task, reportMode, channel);
        lockCorrectionPermit(params, arrange, reportMode);
        changePointHandleService.assertReportAllowed(arrange);
        andonProductionReleaseService.assertAllowed(arrange, automatic ? "设备自动报工" : "人工报工");
        repairProductionReleaseService.assertAllowed(arrange, automatic ? "设备自动报工" : "人工报工");

        MesDeviceGateDecision deviceDecision = deviceGateService.check(
                automatic ? MesDeviceGateStageEnum.AUTO_REPORT : MesDeviceGateStageEnum.REPORT,
                arrange, params.getDeviceCode(), params.getWorksiteCode());
        if (!Boolean.TRUE.equals(deviceDecision.getAllowed())) {
            throw new CheckedException("设备运行门阻断报工：" + deviceDecision.getMessage());
        }
        params.setDeviceCode(deviceDecision.getDeviceCode());
        params.setDeviceName(deviceDecision.getDeviceName());
        params.setWorksiteCode(deviceDecision.getWorksiteCode());
        params.setDeviceGateStage(deviceDecision.getStage());
        params.setDeviceGateAllowed(deviceDecision.getAllowed());
        params.setDeviceGateState(deviceDecision.getDeviceState());
        params.setDeviceGateStateName(deviceDecision.getDeviceStateName());
        params.setDeviceGateMessage(deviceDecision.getMessage());
        params.setDeviceGateCheckTime(deviceDecision.getCheckTime());

        MesReportExtensionContext extensionContext = extensionContext(params, arrange, task, reportMode, channel);
        params.setExtensionEvidenceSnapshot(reportExtensionChain.validate(extensionContext));

        // 人员、实际工位、工装、条码物料、重复过站和逐标识前置工序统一在报工事务内校验。
        stationPassService.validate(params, arrange, task, reportMode, channel);

        MesProcessTimingGuard.Decision timing = processTimingGuard.validate(arrange, reportMode,
                params.getTransferCode(), params.getExecutionKind(), params.getIdentifierRouteVersion(), binding,
                MesTimingExceptionFacts.good(captured, params.getPassNum()));

        MesQualityGateDecision gate;
        if ("REWORK".equals(task.getResourceType())) {
            // 返工产出只允许进入隔离库存，必须由返工复检结论按数量解除隔离。
            gate = new MesQualityGateDecision().setAllowed(Boolean.TRUE).setPassed(Boolean.FALSE)
                    .setGateMode("BLOCK").setPhase("REPORT").setMessage("返工产出等待复检，当前数量保持隔离");
        } else {
            gate = qualityGateService.checkReport(arrange.getProduceTaskCode());
            // 每次报工自然触发上一次待判数量同步，避免依赖人工进入台账点“同步”。
            gate = qualityQuantityService.reconcile(arrange.getProduceTaskCode(), gate);
        }
        if (!Boolean.TRUE.equals(gate.getAllowed())) {
            throw new CheckedException("质量门阻断报工：" + gate.getMessage());
        }
        boolean qualityNotRequired = "NONE".equals(gate.getGateMode())
                && (gate.getItems() == null || gate.getItems().isEmpty());
        boolean qualityReleased = qualityNotRequired || Boolean.TRUE.equals(gate.getPassed());
        String gateStatus;
        if ("REWORK".equals(task.getResourceType())) {
            gateStatus = "PENDING";
        } else if (qualityNotRequired) {
            gateStatus = "NONE";
        } else if (Boolean.TRUE.equals(gate.getPassed())) {
            gateStatus = "PASSED";
        } else if ("WARN".equals(gate.getGateMode()) && StrUtil.startWith(gate.getMessage(), "质量门预警")) {
            gateStatus = "WARN";
        } else {
            gateStatus = "PENDING";
        }
        params.setQualityGateMode(gate.getGateMode());
        params.setQualityGateStatus(gateStatus);
        params.setQualityGateMessage(gate.getMessage());
        MesQualityEvidenceSupport.Evidence qualityEvidence = MesQualityEvidenceSupport.capture(gate);
        params.setQualityTaskIds(qualityEvidence.taskIdsJson());
        params.setQualityEvidenceFingerprint(qualityEvidence.fingerprint());
        params.setQualityEvidenceSnapshot(qualityEvidence.snapshotJson());
        params.setQualityQuantityStatus(qualityNotRequired ? QualityQuantityStatusEnum.NOT_REQUIRED.name()
                : (qualityReleased ? QualityQuantityStatusEnum.RELEASED.name()
                : QualityQuantityStatusEnum.PENDING.name()));
        params.setQualityReleasedNum(qualityReleased ? params.getPassNum() : BigDecimal.ZERO);
        params.setQualityPendingNum(qualityReleased ? BigDecimal.ZERO : params.getPassNum());
        params.setQualityRejectedNum(BigDecimal.ZERO);
        if (routeReturn) {
            // 退回路线只补记工序执行证据，不生成第二份产出库存，也不进入质量数量FIFO分配。
            // 质量门仍在上方执行并冻结证据，真正的不合格处置继续走正式返工工单。
            params.setQualityQuantityStatus(QualityQuantityStatusEnum.NOT_REQUIRED.name());
            params.setQualityReleasedNum(BigDecimal.ZERO);
            params.setQualityPendingNum(BigDecimal.ZERO);
            params.setQualityRejectedNum(BigDecimal.ZERO);
        }

        // 校验产出物料可用性
        var materialResponse = matService.getDetails(params.getMatCode());
        MdmMaterialEntity mat = materialResponse == null ? null : materialResponse.getData();
        if (materialResponse == null || !materialResponse.isOk() || mat == null
                || !Boolean.TRUE.equals(mat.getStatus())
                || !Objects.equals(mat.getMaterialCode(), params.getMatCode())
                || !Objects.equals(mat.getTenantId(), TenantContextHolder.getTenantId())
                || !Objects.equals(mat.getFactoryCode(), factory)) {
            throw new CheckedException("产出物料不可用，报工失败");
        }
        params.setMatUnit(mat.getBasicUnit());
        if (StrUtil.isBlank(params.getMatUnit()))
            throw new CheckedException("产出物料缺少基本单位，不能生成无单位报工事实");

        // 当前的派工任务
        params.setTaskArrange(arrange);
        params.setTask(task);
        // 调用不同的报工方式进行报工处理
        MesTaskReportResult reportResult = processOperationFactory.execute(reportMode.getCode(), params);
        MesTaskReportEntity report = reportResult.getReport();
        report.setReportUnit(params.getMatUnit());
        report.setReportMode(reportMode.getCode());
        report.setTimingAcceptedAt(timing.acceptedAt());
        report.setTimingEvidenceJson(timing.evidenceJson());
        identifierBindings.stampReport(binding, report);
        require(this.save(report), "GOOD报工主记录必要写失败，整次报工回滚");
        verifySavedReport(report);
        processOperationFactory.persistAssociations(reportMode.getCode(), params, reportResult);
        consumeCorrectionPermit(params, report);
        identifierMappingService.recordSuccessfulUse(report, params);
        reportExtensionChain.afterPersist(extensionContext, report);
        if (!routeReturn) {
            processOperationFactory.applyInventory(reportMode.getCode(), params);
        }
        // 保存操作记录
        MesTaskReportRecordEntity record = new MesTaskReportRecordEntity();
        // 操作履历有独立主键，不能复制报工ID；同笔包装还会追加第二份履历。
        BeanUtils.copyProperties(report, record, "id");
        record.setReportId(report.getId());
        record.setType(ReportRecordTypeEnum.REPORT.name());
        record.setTriggerType(report.getType());
        record.setContent(reportResult.getReportMessage());
        record.setFactoryCode(arrange.getFactoryCode());
        saveRequiredReportRecord(record);

        // 包装装箱单独记录一份操作记录
        if (StrUtil.isNotBlank(reportResult.getWrapMessage())) {
            MesTaskReportRecordEntity wrapRecord = new MesTaskReportRecordEntity();
            BeanUtils.copyProperties(report, wrapRecord, "id");
            wrapRecord.setReportId(report.getId());
            wrapRecord.setReportId(report.getId());
            wrapRecord.setType(ReportRecordTypeEnum.WRAP.name());
            wrapRecord.setTriggerType(report.getType());
            wrapRecord.setContent(reportResult.getWrapMessage());
            wrapRecord.setFactoryCode(arrange.getFactoryCode());
            saveRequiredReportRecord(wrapRecord);
        }

        // 乐观条件更新，防止多人或设备并发报工造成超报和数量覆盖。
        if (!routeReturn) {
            updateArrangeCompletedQuantity(arrange, report.getReportNum(), report.getQualityReleasedNum());
            // 更新生产工单状态
            updateTask(arrange, task, report, reportParent.getId());
        }

        // 成功过站履历必须和报工、物料消耗、产出及数量回写原子提交。
        stationPassService.recordAccepted(params, arrange, task, reportMode, report);
        routeReturnCoordinator.completeIfResume(params, arrange, report);
        // ERP不可用不能回滚已提交报工；同一本地事务只写MES Outbox，网络转交由后台工作器完成。
        if (!routeReturn) erpPostingOutbox.captureCompletion(task, report);
        // 永久回执最后追加，任一台账/计数/库存/过站步骤失败时连同原正文一起回滚。
        reportCommands.accept(captured, report);
        // 动态实例、节点操作、成功回执仍在同一本地事务；任一步失败全部回滚。
        identifierBindings.complete(binding, report, captured);
        reworkIdentifiers.complete(reworkIdentifier, report);
        params.setReportId(report.getId());
    }

    /**
     * 原报告必须实际落库且保留原单位/模式/标识和精确数量，才能继续耗料与计数。
     */
    private void verifySavedReport(MesTaskReportEntity expected) {
        var saved = getById(expected.getId());
        require(saved != null && Objects.equals(saved.getTenantId(), expected.getTenantId())
                        && Objects.equals(saved.getFactoryCode(), expected.getFactoryCode())
                        && Objects.equals(saved.getRequestId(), expected.getRequestId())
                        && Objects.equals(saved.getTaskCode(), expected.getTaskCode())
                        && Objects.equals(saved.getProduceTaskCode(), expected.getProduceTaskCode())
                        && Objects.equals(saved.getArrangeCode(), expected.getArrangeCode())
                        && Objects.equals(saved.getMatCode(), expected.getMatCode())
                        && Objects.equals(saved.getReportUnit(), expected.getReportUnit())
                        && Objects.equals(saved.getReportMode(), expected.getReportMode())
                        && Objects.equals(saved.getTimingAcceptedAt(), expected.getTimingAcceptedAt())
                        && Objects.equals(saved.getTimingEvidenceJson(), expected.getTimingEvidenceJson())
                        && Objects.equals(saved.getTransferCode(), expected.getTransferCode())
                        && Objects.equals(saved.getSubmittedIdentifier(), expected.getSubmittedIdentifier())
                        && Objects.equals(saved.getIdentifierMappingId(), expected.getIdentifierMappingId())
                        && Objects.equals(saved.getIdentifierType(), expected.getIdentifierType())
                        && Objects.equals(saved.getIdentifierResolutionSnapshot(), expected.getIdentifierResolutionSnapshot())
                        && Objects.equals(saved.getExecutionKind(), expected.getExecutionKind())
                        && Objects.equals(saved.getStatus(), expected.getStatus())
                        && sameNullableAmount(saved.getReportNum(), expected.getReportNum())
                        && sameNullableAmount(saved.getEffectiveReportNum(), expected.getEffectiveReportNum())
                        && sameNullableAmount(saved.getCorrectedReportNum(), expected.getCorrectedReportNum())
                        && sameNullableAmount(saved.getQualityReleasedNum(), expected.getQualityReleasedNum())
                        && sameNullableAmount(saved.getQualityPendingNum(), expected.getQualityPendingNum())
                        && sameNullableAmount(saved.getQualityRejectedNum(), expected.getQualityRejectedNum()),
                "GOOD原报告关联、单位或精确数量回读不一致，禁止继续计产");
    }

    /**
     * 报工/装箱履历是本事务的必要事实，不允许保存失败后仍累计数量并接受原号。
     */
    private void saveRequiredReportRecord(MesTaskReportRecordEntity expected) {
        expected.setTenantId(TenantContextHolder.getTenantId());
        require(recordService.save(expected), "GOOD操作履历必要写失败，整次报工回滚");
        var saved = recordService.getById(expected.getId());
        require(saved != null && Objects.equals(saved.getTenantId(), expected.getTenantId())
                        && Objects.equals(saved.getFactoryCode(), expected.getFactoryCode())
                        && Objects.equals(saved.getReportId(), expected.getReportId())
                        && Objects.equals(saved.getTaskCode(), expected.getTaskCode())
                        && Objects.equals(saved.getProduceTaskCode(), expected.getProduceTaskCode())
                        && Objects.equals(saved.getArrangeCode(), expected.getArrangeCode())
                        && Objects.equals(saved.getType(), expected.getType())
                        && Objects.equals(saved.getTriggerType(), expected.getTriggerType())
                        && Objects.equals(saved.getWorkerCode(), expected.getWorkerCode())
                        && Objects.equals(saved.getContent(), expected.getContent())
                        && sameNullableAmount(saved.getReportNum(), expected.getReportNum()),
                "GOOD操作履历关联或原数量回读不一致，整次报工回滚");
    }

    private boolean sameNullableAmount(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private MesReportExtensionContext extensionContext(MesTaskReportVO params, MesTaskArrangeEntity arrange,
                                                       MesTaskEntity task, ReportModeEnum mode,
                                                       MesReportChannelEnum channel) {
        return MesReportExtensionContext.builder()
                .requestId(params.getRequestId()).factoryCode(arrange.getFactoryCode())
                .taskCode(task.getCode()).arrangeCode(arrange.getCode())
                .produceTaskCode(arrange.getProduceTaskCode()).materialCode(arrange.getMatCode())
                .routeCode(arrange.getRouteCode()).worksiteCode(params.getWorksiteCode())
                .deviceCode(params.getDeviceCode()).canonicalIdentifier(params.getTransferCode())
                .submittedIdentifier(params.getSubmittedIdentifier()).identifierType(params.getIdentifierType())
                .quantity(params.getPassNum()).reportMode(mode).channel(channel).build();
    }

    private void prepareRequestId(MesTaskReportVO params) {
        params.setRequestId(normalizeRequestId(params.getRequestId()));
    }

    /**
     * 受控重报许可只补回已批准纠正释放的数量，不替代正常报工门和库存/质量校验。
     */
    private void lockCorrectionPermit(MesTaskReportVO params, MesTaskArrangeEntity arrange,
                                      ReportModeEnum reportMode) {
        if (StrUtil.isBlank(params.getCorrectionPermitNo())) return;
        String permitNo = params.getCorrectionPermitNo().trim();
        MesReportReverseRequestEntity permit = reverseRequestMapper.selectOne(
                new LambdaQueryWrapper<MesReportReverseRequestEntity>()
                        .eq(MesReportReverseRequestEntity::getReReportPermitNo, permitNo)
                        .eq(MesReportReverseRequestEntity::getFactoryCode, arrange.getFactoryCode())
                        .last("FOR UPDATE"));
        if (permit == null || !"APPROVED".equals(permit.getStatus())
                || !"ACTIVE".equals(permit.getReReportPermitStatus())) {
            throw new CheckedException("受控重报许可不存在、未批准或已失效");
        }
        if (permit.getReReportExpireTime() == null || !permit.getReReportExpireTime().isAfter(LocalDateTime.now())) {
            throw new CheckedException("受控重报许可已过期，请重新复核现场差异");
        }
        if (!Objects.equals(permit.getTaskCode(), arrange.getTaskCode())
                || !Objects.equals(permit.getProduceTaskCode(), arrange.getProduceTaskCode())
                || !Objects.equals(permit.getArrangeCode(), arrange.getCode())
                || !Objects.equals(permit.getReportMode(), reportMode.getCode())) {
            throw new CheckedException("受控重报许可与当前工单、工序、派工或报工模式不一致");
        }
        BigDecimal remaining = Objects.requireNonNullElse(permit.getReReportAllowedQuantity(), BigDecimal.ZERO)
                .subtract(Objects.requireNonNullElse(permit.getReReportUsedQuantity(), BigDecimal.ZERO));
        if (params.getPassNum() == null || params.getPassNum().signum() <= 0
                || params.getPassNum().compareTo(remaining) > 0) {
            throw new CheckedException("本次重报数量超过许可剩余量：" + remaining.stripTrailingZeros().toPlainString());
        }
        params.setCorrectionPermitNo(permitNo);
        params.setCorrectionPermitId(permit.getId());
    }

    private void consumeCorrectionPermit(MesTaskReportVO params, MesTaskReportEntity report) {
        if (params.getCorrectionPermitId() == null) return;
        MesReportReverseRequestEntity permit = reverseRequestMapper.selectById(params.getCorrectionPermitId());
        if (permit == null || !"ACTIVE".equals(permit.getReReportPermitStatus())) {
            throw new CheckedException("受控重报许可在报工事务中已失效");
        }
        BigDecimal before = Objects.requireNonNullElse(permit.getReReportUsedQuantity(), BigDecimal.ZERO);
        BigDecimal after = before.add(report.getReportNum());
        BigDecimal allowed = permit.getReReportAllowedQuantity();
        if (allowed == null || after.compareTo(allowed) > 0) {
            throw new CheckedException("受控重报许可累计使用数量超限");
        }
        boolean updated = reverseRequestMapper.update(null,
                new LambdaUpdateWrapper<MesReportReverseRequestEntity>()
                        .eq(MesReportReverseRequestEntity::getId, permit.getId())
                        .eq(MesReportReverseRequestEntity::getReReportPermitStatus, "ACTIVE")
                        .eq(MesReportReverseRequestEntity::getReReportUsedQuantity, before)
                        .set(MesReportReverseRequestEntity::getReReportUsedQuantity, after)
                        .set(MesReportReverseRequestEntity::getReReportPermitStatus,
                                after.compareTo(allowed) == 0 ? "USED" : "ACTIVE")) == 1;
        if (!updated) throw new CheckedException("受控重报许可已被其他报工占用，请刷新后重试");
    }

    private String normalizeRequestId(String requestId) {
        String normalized = StrUtil.isBlank(requestId) ? UUID.randomUUID().toString() : requestId.trim();
        if (normalized.length() < 8) {
            throw new CheckedException("业务请求号长度不能少于8位");
        }
        if (normalized.length() > 64) {
            throw new CheckedException("业务请求号长度不能超过64位");
        }
        return normalized;
    }

    /**
     * 使用数据库中的工单/派工数据覆盖前端上下文字段，并执行报工通用控制。
     */
    private void prepareReportContext(MesTaskReportVO params, MesTaskArrangeEntity arrange,
                                      MesTaskEntity task, ReportModeEnum reportMode,
                                      MesReportChannelEnum channel) {
        boolean automatic = channel.isAutomatic();
        if (params.getPassNum() == null || params.getPassNum().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CheckedException("合格报工数量必须大于0");
        }

        BigDecimal completed = Objects.requireNonNullElse(arrange.getCompleteNum(), BigDecimal.ZERO);
        if (reportMode.isIdentifierRequired()) {
            if (StrUtil.isBlank(params.getTransferCode())) {
                throw new CheckedException(reportMode.getName() + "必须提供条码、流转卡或容器码");
            }
            // 一物一码和流转卡由统一过站防错门记录拒绝原因；包装模式仍在这里防止容器复用。
            if (reportMode.isPackaging()) {
                boolean duplicated = this.count(new LambdaQueryWrapper<MesTaskReportEntity>()
                        .eq(MesTaskReportEntity::getProduceTaskCode, arrange.getProduceTaskCode())
                        .eq(MesTaskReportEntity::getTransferCode, params.getTransferCode().trim())
                        .and(item -> item.isNull(MesTaskReportEntity::getStatus)
                                .or().ne(MesTaskReportEntity::getStatus, ReportStatusEnum.REVERSED.name()))) > 0;
                if (duplicated) {
                    throw new CheckedException("该包装标识已在当前工序报工，请勿重复提交");
                }
            }
            params.setTransferCode(params.getTransferCode().trim());
        }

        params.setTaskCode(arrange.getTaskCode());
        params.setProduceTaskCode(arrange.getProduceTaskCode());
        params.setMatCode(arrange.getMatCode());
        params.setMatName(arrange.getMatName());
        params.setMatSpec(arrange.getMatSpec());
        params.setNoticeNum(arrange.getNoticeNum());
        params.setCompleteNum(completed);
        params.setTaskType(task.getType());
        params.setFactoryCode(arrange.getFactoryCode());
        params.setWrapRuleCode(StrUtil.blankToDefault(params.getWrapRuleCode(), arrange.getWrapRuleCode()));
        WorksiteTypeEnum configuredTrigger = WorksiteTypeEnum.fromCode(arrange.getWorksiteTypes());
        if (automatic && configuredTrigger != WorksiteTypeEnum.AUTO) {
            throw new CheckedException("当前派工任务不是自动报工工位");
        }
        if (!automatic && configuredTrigger == WorksiteTypeEnum.AUTO) {
            if (StrUtil.isBlank(params.getManualOverrideReason())) {
                throw new CheckedException("自动报工工位人工接管时必须填写原因");
            }
            if (params.getManualOverrideReason().trim().length() > 500) {
                throw new CheckedException("人工接管原因长度不能超过500位");
            }
            params.setManualOverrideReason(params.getManualOverrideReason().trim());
        }
        params.setType(switch (channel) {
            case DEVICE -> WorksiteTypeEnum.AUTO.name();
            case INTEGRATION -> "API";
            default -> WorksiteTypeEnum.MANUAL.name();
        });

        if (reportMode == ReportModeEnum.PACKAGE_ONE_CODE) {
            if (params.getPassNum().compareTo(BigDecimal.ONE) != 0) {
                throw new CheckedException("包装一物一码报工每次只能报工1件");
            }
            if (params.getCapacity() == null && StrUtil.isNotBlank(arrange.getWrapCapacity())) {
                try {
                    params.setCapacity(Integer.parseInt(arrange.getWrapCapacity()));
                } catch (NumberFormatException exception) {
                    throw new CheckedException("包装容量配置不是有效整数");
                }
            }
            if (params.getCapacity() == null || params.getCapacity() <= 0) {
                throw new CheckedException("包装一物一码报工必须配置大于0的包装容量");
            }
        }
    }

    /**
     * 调用前已有原工单业务锁和根/派工行锁；新报告只认精确实际父工序。
     */
    private MesProduceTaskEntity requireReportParents(MesTaskArrangeEntity arrange, MesTaskEntity task,
                                                      boolean approvedReturn) {
        // 质量来源以实际任务单号识别；同厂重复单号必须先治理，不能按根工单挑一条绕过歧义。
        var parents = produceTaskService.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, arrange.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode())
                .last("LIMIT 2 FOR UPDATE"));
        require(parents != null && parents.size() == 1, "报工工序任务单号缺失或重复，请先治理实际父工序");
        MesProduceTaskEntity parent = parents.get(0);
        MesReportParentStateGuard.validate(arrange, task, parent, approvedReturn);
        return parent;
    }

    private void updateArrangeCompletedQuantity(MesTaskArrangeEntity arrange, BigDecimal reportNum,
                                                BigDecimal releasedNum) {
        BigDecimal before = arrange.getCompleteNum();
        BigDecimal after = Objects.requireNonNullElse(before, BigDecimal.ZERO).add(reportNum);
        LambdaUpdateWrapper<MesTaskArrangeEntity> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(MesTaskArrangeEntity::getId, arrange.getId())
                .eq(MesTaskArrangeEntity::getTenantId, arrange.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesTaskArrangeEntity::getTaskCode, arrange.getTaskCode());
        if (before == null) {
            wrapper.isNull(MesTaskArrangeEntity::getCompleteNum);
        } else {
            wrapper.eq(MesTaskArrangeEntity::getCompleteNum, before);
        }
        // 同事务内可能已放行历史待检量，只增本次放行量，不能覆盖当前数据库值。
        boolean updated = arrangeService.update(wrapper.set(MesTaskArrangeEntity::getCompleteNum, after)
                .setSql("quality_released_num = COALESCE(quality_released_num, 0) + {0}",
                        Objects.requireNonNullElse(releasedNum, BigDecimal.ZERO)));
        if (!updated) {
            throw new CheckedException("报工数量已被其他操作更新，请刷新任务后重试");
        }
        arrange.setCompleteNum(after);
    }

    /**
     * 更新生产工单状态
     *
     * @param arrange
     */
    private void updateTask(MesTaskArrangeEntity arrange, MesTaskEntity task, MesTaskReportEntity report,
                            Long parentId) {
        // 质量同步可能更新父质量量；按锁定的精确原行重读，不能回写检查前的旧实体。
        MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, arrange.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesProduceTaskEntity::getTaskCode, arrange.getTaskCode())
                .eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode())
                .eq(MesProduceTaskEntity::getId, parentId).last("FOR UPDATE"));
        if (produceTask == null) {
            throw new CheckedException("未找到派工任务对应的工序任务");
        }
        MesReportParentStateGuard.validate(arrange, task, produceTask, false);
        BigDecimal produceBefore = produceTask.getCompleteNum();
        BigDecimal produceAfter = Objects.requireNonNullElse(produceBefore, BigDecimal.ZERO).add(report.getReportNum());
        BigDecimal releasedBefore = Objects.requireNonNullElse(produceTask.getQualityReleasedNum(), BigDecimal.ZERO);
        BigDecimal releasedAfter = releasedBefore.add(Objects.requireNonNullElse(report.getQualityReleasedNum(), BigDecimal.ZERO));
        LambdaUpdateWrapper<MesProduceTaskEntity> produceWrapper = Wrappers.lambdaUpdate();
        produceWrapper.eq(MesProduceTaskEntity::getId, produceTask.getId());
        if (produceBefore == null) {
            produceWrapper.isNull(MesProduceTaskEntity::getCompleteNum);
        } else {
            produceWrapper.eq(MesProduceTaskEntity::getCompleteNum, produceBefore);
        }
        produceWrapper.set(MesProduceTaskEntity::getCompleteNum, produceAfter);
        produceWrapper.set(MesProduceTaskEntity::getQualityReleasedNum, releasedAfter);
        boolean transferBatchReached = Boolean.TRUE.equals(produceTask.getOverlapEnabled())
                && produceTask.getTransferReleasedTime() == null
                && produceTask.getTransferBatchQuantity() != null
                && produceTask.getTransferBatchQuantity().signum() > 0
                && releasedBefore
                .compareTo(produceTask.getTransferBatchQuantity()) < 0
                && releasedAfter.compareTo(produceTask.getTransferBatchQuantity()) >= 0;
        if (transferBatchReached) {
            LocalDateTime releaseTime = Objects.requireNonNullElse(report.getReportTime(), LocalDateTime.now())
                    .plusMinutes(Objects.requireNonNullElse(produceTask.getTransferMinutes(), 0L));
            produceWrapper.set(MesProduceTaskEntity::getTransferReleasedTime, releaseTime);
        }
        if (!produceTaskService.update(produceWrapper)) {
            throw new CheckedException("工序完成数量已被其他操作更新，请刷新任务后重试");
        }

        // 末尾工序的本次合格数进入待入库量，不能重复累计派工单历史完成总量。
        if (Objects.nonNull(arrange.getLastRoute()) && arrange.getLastRoute()) {
            LambdaUpdateWrapper<MesTaskEntity> taskWrapper = Wrappers.lambdaUpdate();
            taskWrapper.eq(MesTaskEntity::getId, task.getId())
                    .eq(MesTaskEntity::getTenantId, task.getTenantId())
                    .eq(MesTaskEntity::getFactoryCode, task.getFactoryCode())
                    .eq(MesTaskEntity::getCode, task.getCode());
            // 质量同步可能刚增加历史放行量；根行已锁定，按当前值原子增加本次量。
            if (!taskService.update(taskWrapper.setSql("un_in_stock_num = COALESCE(un_in_stock_num, 0) + {0}",
                    Objects.requireNonNullElse(report.getQualityReleasedNum(), BigDecimal.ZERO)))) {
                throw new CheckedException("工单待入库数量已被其他操作更新，请刷新任务后重试");
            }
        }
    }

    /**
     * 生产报工-不合格品上报
     *
     * <p>在原生产事务冻结实际不良计量并核必要写；不产生库存或质量放行。
     * 只有显式FEED才登记本次尚未记账的实际投料耗用，声明不自动二次扣料。
     * 成功原号可独立恢复，不重新读取当前计量主数据或再次累加数量。</p>
     *
     * @param params 原请求号、不良项、原数量及派工/标识；其他归属由真实派工确定
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void unqualified(MesTaskReportUnqualifiedVO params) {
        if (params == null || StrUtil.isBlank(params.getArrangeCode())) {
            throw new CheckedException("派工单号不能为空");
        }
        if (params.getUnPassNum() == null || params.getUnPassNum().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CheckedException("不合格数量必须大于0");
        }
        if (StrUtil.isBlank(params.getUnqualifiedType())) {
            throw new CheckedException("请选择不良品项");
        }
        String factory = factoryAccess.require("write");
        params.setMaterialAccountingMode(productionMaterials.mode(params.getMaterialAccountingMode()));
        params.setRequestId(normalizeRequestId(params.getRequestId()));
        // 原成功恢复先于当前生产状态/模板门；不能因已经完工或MDM变更而重新记产。
        if (replayUnqualifiedReport(params, factory)) return;
        MesTaskArrangeEntity ownership = taskLock.arrange(params.getArrangeCode());
        MesTaskArrangeEntity arrange = arrangeService.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, params.getArrangeCode()).last("FOR UPDATE"));

        if (Objects.isNull(arrange)) {
            throw new CheckedException("未找到派工任务");
        }
        taskLock.assertArrange(ownership, arrange);
        // 首次无锁观察后另一端可能先成功；取得原工单锁后必须重查，不能再次建报告/累加数量。
        if (replayUnqualifiedReport(params, factory)) return;
        terminalExecutionGuard.assertUnoccupied(arrange);
        if (!MesProduceTaskStatusEnum.PRODUCT.name().equals(arrange.getStatus())) {
            throw new CheckedException("生产任务未在进行中，请先对任务开工");
        }

        materialBindings.assertNgAccounting(arrange, params.getMaterialAccountingMode());

        ReportModeEnum reportMode = ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType());
        if (reportMode.isIdentifierRequired() && StrUtil.isBlank(params.getTransferCode())) {
            throw new CheckedException("当前报工模式必须提供条码或流转卡");
        }
        // 原父工单锁先于标识/投料锁；不把请求DTO中的工单或规范码当作可信归属。
        MesTaskEntity task = taskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("FOR UPDATE"));
        if (task == null) {
            throw new CheckedException("未找到派工任务对应的生产工单");
        }
        requireReportParents(arrange, task, false);
        // 五对象模式均复用同一静态解析器，包括可选批次码；保留params原始正文用于原号与时间例外。
        // 新NG的规范码用于全部生产事实，原始输入及实际映射单独留证；动态节点已在上方明确拒绝。
        String originalIdentifier = MesNgIdentificationCodec.original(params.getTransferCode());
        if (originalIdentifier != null && originalIdentifier.length() > 200) {
            throw new CheckedException("原始生产标识长度不能超过200字符（含首尾空白）");
        }
        MesTaskReportVO identifierInput = new MesTaskReportVO();
        identifierInput.setRequestId(params.getRequestId());
        identifierInput.setTransferCode(originalIdentifier);
        identifierInput.setPassNum(params.getUnPassNum());
        MesReworkIdentifierUsageService.Prepared reworkIdentifier = reworkIdentifiers.prepare(
                identifierInput, arrange, task, reportMode, "NG");
        if (reworkIdentifier != null) {
            /*
             * V2的NG来源协议继续保存内部规范码解析；真实现场外码及原实例证据进入
             * mes_rework_identifier_usage，避免改写既有NG出生/库存协议和历史SHA。
             */
            identifierBindings.prepare(identifierInput, arrange, task, reportMode, null);
            require(Objects.equals(identifierInput.getTransferCode(), reworkIdentifier.canonicalIdentifier())
                            && identifierInput.getIdentifierMappingId() == null
                            && "CANONICAL".equals(identifierInput.getIdentifierType()),
                    "返工原实例的内部SN与当前静态标识治理冲突，请先治理映射后再报NG");
        } else {
            if (!"NONE".equals(executionTemplateService.contract(arrange).getIdentifierNodePolicy().getAction())) {
                throw new CheckedException("正常生产动态换码尚未开放不合格执行链，禁止用不合格入口绕过节点事务");
            }
            identifierBindings.prepare(identifierInput, arrange, task, reportMode, null);
        }
        MomUser currentUser = SecurityUtils.getUser();
        // 保存不合格品上报信息
        MesTaskReportUnqualifiedEntity unqualified = new MesTaskReportUnqualifiedEntity();
        unqualified.setRequestId(params.getRequestId());
        unqualified.setTaskCode(arrange.getTaskCode());
        unqualified.setArrangeCode(arrange.getCode());
        unqualified.setProduceTaskCode(arrange.getProduceTaskCode());
        unqualified.setMatCode(arrange.getMatCode());
        unqualified.setMatName(arrange.getMatName());
        unqualified.setMatSpec(arrange.getMatSpec());
        unqualified.setReportTime(LocalDateTime.now());
        unqualified.setUnqualifiedNum(params.getUnPassNum());
        unqualified.setType(params.getUnqualifiedType());
        unqualified.setTransferCode(identifierInput.getTransferCode());
        unqualified.setSubmittedIdentifier(originalIdentifier);
        unqualified.setIdentifierMappingId(identifierInput.getIdentifierMappingId());
        unqualified.setIdentifierType(identifierInput.getIdentifierType());
        unqualified.setIdentifierResolutionSnapshot(identifierInput.getIdentifierResolutionSnapshot());
        unqualified.setWorkerCode(currentUser.getUsername());
        unqualified.setWorkerName(currentUser.getName());
        unqualified.setFactoryCode(arrange.getFactoryCode());
        unqualifiedService.saveFrozenSource(unqualified, arrange, reportMode,
                com.kitex.mom.mdm.support.MdmProcessTimingPolicy.encodeEvidence(params));

        // 真实NG和耗用仍在同一原生产事务，没有历史补建或假GOOD报告。
        if ("FEED".equals(params.getMaterialAccountingMode()))
            productionMaterials.recordFeed(unqualified, arrange, task);

        // 保存操作记录
        MesTaskReportRecordEntity record = new MesTaskReportRecordEntity();
        BeanUtils.copyProperties(unqualified, record, "id");
        record.setType(ReportRecordTypeEnum.UNQUALIFIED.name());
        record.setContent(currentUser.getName() + "于"
                + DateUtil.format(unqualified.getReportTime(), "yyyy-MM-dd HH:mm:ss")
                + "进行报工-不合格品上报，不合格数量为：" + unqualified.getUnqualifiedNum());
        if (!recordService.save(record)) {
            throw new CheckedException("不良上报操作记录未保存，整次上报回滚");
        }

        // 派工单以本次数量增量更新，并使用旧值条件避免并发覆盖。
        BigDecimal arrangeBefore = arrange.getUnPassNum();
        BigDecimal arrangeAfter = Objects.requireNonNullElse(arrangeBefore, BigDecimal.ZERO)
                .add(unqualified.getUnqualifiedNum());
        LambdaUpdateWrapper<MesTaskArrangeEntity> arrangeWrapper = Wrappers.lambdaUpdate();
        arrangeWrapper.eq(MesTaskArrangeEntity::getId, arrange.getId());
        if (arrangeBefore == null) {
            arrangeWrapper.isNull(MesTaskArrangeEntity::getUnPassNum);
        } else {
            arrangeWrapper.eq(MesTaskArrangeEntity::getUnPassNum, arrangeBefore);
        }
        if (!arrangeService.update(arrangeWrapper.set(MesTaskArrangeEntity::getUnPassNum, arrangeAfter))) {
            throw new CheckedException("不合格数量已被其他操作更新，请刷新任务后重试");
        }

        // 工单只累加本次不合格数量，不能重复累加派工单历史累计值。
        BigDecimal taskBefore = task.getUnPassNum();
        BigDecimal taskAfter = Objects.requireNonNullElse(taskBefore, BigDecimal.ZERO)
                .add(unqualified.getUnqualifiedNum());
        LambdaUpdateWrapper<MesTaskEntity> taskWrapper = Wrappers.lambdaUpdate();
        taskWrapper.eq(MesTaskEntity::getId, task.getId());
        if (taskBefore == null) {
            taskWrapper.isNull(MesTaskEntity::getUnPassNum);
        } else {
            taskWrapper.eq(MesTaskEntity::getUnPassNum, taskBefore);
        }
        if (!taskService.update(taskWrapper.set(MesTaskEntity::getUnPassNum, taskAfter))) {
            throw new CheckedException("工单不合格数量已被其他操作更新，请刷新任务后重试");
        }

        if (task.getPlanNo() != null || task.getApsBatchNo() != null) {
            sourceQuantityService.record(task, "UNQUALIFIED-REPORT:" + unqualified.getRequestId(),
                    MesSourceQuantityKind.UNQUALIFIED_REPORT, unqualified.getUnqualifiedNum(),
                    String.valueOf(unqualified.getId()),
                    "MES工单【" + task.getCode() + "】派工单【" + arrange.getCode() + "】直接上报不合格【"
                            + unqualified.getUnqualifiedNum() + "】，不良类型【" + unqualified.getType() + "】");
        }

        erpPostingOutbox.captureScrap(task, unqualified);

        reworkIdentifiers.complete(reworkIdentifier, unqualified);

    }

    /**
     * 不良原号恢复只核原业务与身份；历史兼容和新严格标识分开，不访问当前计量主数据。
     */
    private boolean replayUnqualifiedReport(MesTaskReportUnqualifiedVO params, String factory) {
        var existing = unqualifiedService.getOne(new LambdaQueryWrapper<MesTaskReportUnqualifiedEntity>()
                .eq(MesTaskReportUnqualifiedEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportUnqualifiedEntity::getFactoryCode, factory)
                .eq(MesTaskReportUnqualifiedEntity::getRequestId, params.getRequestId()));
        if (existing == null) return false;
        boolean sameIdentifier = MesNgIdentificationCodec.sameInput(existing, params.getTransferCode());
        if (Objects.equals(existing.getArrangeCode(), params.getArrangeCode())
                && existing.getUnqualifiedNum() != null
                && existing.getUnqualifiedNum().compareTo(params.getUnPassNum()) == 0
                && Objects.equals(existing.getType(), params.getUnqualifiedType()) && sameIdentifier
                && (existing.getCapturedActorId() == null
                || Objects.equals(existing.getCapturedActorId(), SecurityUtils.getUser().getId()))) {
            unqualifiedService.verifySource(existing);
            var originalOperation = productionMaterials.original(existing);
            String originalMode = originalOperation == null ? "NONE" : originalOperation.getAccountingMode();
            if (!Objects.equals(originalMode, params.getMaterialAccountingMode())) {
                throw new CheckedException("原不良请求的耗料选择不同；禁止给旧声明补耗料或另号重投");
            }
            return true;
        }
        throw new CheckedException("原不良请求已登记，身份或业务内容不匹配；请核查原号，禁止另号重投");
    }

    /**
     * 生产报工-投料
     *
     * @param params
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void feed(MesTaskReportFeedVO params) {
        if (params == null || StrUtil.isBlank(params.getArrangeCode()))
            throw new CheckedException("投料记录必须有真实派工单");
        String factory = factoryAccess.require("write");
        // BEFORE_COMMIT投料事件可能来自已持有库存行锁的旧链，此处只校验真实归属，不能补取工单锁。
        MesTaskArrangeEntity arrange = arrangeService.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory).eq(MesTaskArrangeEntity::getCode, params.getArrangeCode()));
        if (arrange == null) throw new CheckedException("当前租户工厂未找到投料派工");
        MesTaskEntity feedTask = taskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, factory).eq(MesTaskEntity::getCode, arrange.getTaskCode()));
        if (feedTask == null) throw new CheckedException("当前投料派工缺少生产工单");
        MomUser currentUser = SecurityUtils.getUser();
        // 保存投料信息
        MesTaskReportFeedEntity feed = new MesTaskReportFeedEntity();
        feed.setArrangeCode(params.getArrangeCode());
        feed.setTaskCode(arrange.getTaskCode());
        feed.setProduceTaskCode(arrange.getProduceTaskCode());
        feed.setMatCode(params.getMatCode());
        feed.setMatName(params.getMatName());
        feed.setMatSpec(params.getMatSpec());
        feed.setReportNum(params.getNoticeNum());
        feed.setReportTime(LocalDateTime.now());
        feed.setFeedNum(params.getFeedNum());
        feed.setWorkerCode(currentUser.getUsername());
        feed.setWorkerName(currentUser.getName());
        feed.setInspectionTaskId(params.getInspectionTaskId());
        feed.setInspectionResult(params.getInspectionResult());
        feed.setFactoryCode(factory);
        if (!feedReportService.save(feed)) {
            throw new CheckedException("投料履历必要写失败，整笔投料回滚");
        }

        // 保存操作记录
        MesTaskReportRecordEntity record = new MesTaskReportRecordEntity();
        BeanUtils.copyProperties(feed, record, "id");
        record.setType(ReportRecordTypeEnum.FEED.name());
        record.setContent(currentUser.getName() + "于" + DateUtil.format(feed.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行报工-投料，投料数量为：" + feed.getFeedNum());
        if (!recordService.save(record)) {
            throw new CheckedException("投料操作履历必要写失败，整笔投料回滚");
        }

        erpPostingOutbox.captureMaterial(feedTask, feed);

    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleFeedEvent(FeedRecordSaveEvent event) {
        log.debug("投料记录保存");
        MesTaskReportFeedVO params = event.getVo();
        this.feed(params);
    }

}
