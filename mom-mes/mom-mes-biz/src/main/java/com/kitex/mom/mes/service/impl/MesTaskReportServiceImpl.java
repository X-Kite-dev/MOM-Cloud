package com.kitex.mom.mes.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
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
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.process.ProcessOperationFactory;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.mes.support.MesQualityEvidenceSupport;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

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
    private final MesReportExtensionChain reportExtensionChain;
    private final MesActualFactoryAccessService factoryAccess;


    /**
     * 生产报工
     *
     * @param params
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void report(MesTaskReportVO params) {
        reportInternal(params, MesReportChannelEnum.STATION);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reportMobile(MesTaskReportVO params) {
        reportInternal(params, MesReportChannelEnum.MOBILE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reportIntegration(MesTaskReportVO params) {
        reportInternal(params, MesReportChannelEnum.INTEGRATION);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reportAuto(MesTaskReportVO params) {
        reportInternal(params, MesReportChannelEnum.DEVICE);
    }

    private void reportInternal(MesTaskReportVO params, MesReportChannelEnum channel) {
        boolean automatic = channel.isAutomatic();
        if (params == null || StrUtil.isBlank(params.getArrangeCode())) {
            throw new CheckedException("派工单号不能为空");
        }
        String factory = factoryAccess.require("write");
        MesTaskArrangeEntity arrange = arrangeService.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, params.getArrangeCode()).last("FOR UPDATE"));

        if (Objects.isNull(arrange)) {
            throw new CheckedException("未找到派工任务");
        }
        if (!MesProduceTaskStatusEnum.PRODUCT.name().equals(arrange.getStatus())) {
            throw new CheckedException("生产任务未在进行中，请先对任务开工");
        }

        MesTaskEntity task = taskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("FOR UPDATE"));
        if (Objects.isNull(task)) {
            throw new CheckedException("未找到派工任务对应的生产工单");
        }

        prepareRequestId(params);
        if (automatic) {
            if (StrUtil.isBlank(params.getSourceEventId()) || StrUtil.isBlank(params.getDeviceCode())) {
                throw new CheckedException("设备自动报工必须提供来源事件号和设备编码");
            }
            MesTaskReportEntity sourceOwner = this.getOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                    .eq(MesTaskReportEntity::getFactoryCode, factory)
                    .eq(MesTaskReportEntity::getSourceEventId, params.getSourceEventId()), false);
            if (sourceOwner != null && !Objects.equals(sourceOwner.getRequestId(), params.getRequestId())) {
                throw new CheckedException("设备来源事件已生成其他报工记录，禁止重复记产");
            }
        }
        MesTaskReportEntity existingReport = this.getOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getFactoryCode, factory)
                .eq(MesTaskReportEntity::getRequestId, params.getRequestId()));
        if (existingReport != null) {
            String existingSubmitted = StrUtil.blankToDefault(existingReport.getSubmittedIdentifier(),
                    existingReport.getTransferCode());
            if (Objects.equals(existingReport.getArrangeCode(), arrange.getCode())
                    && existingReport.getReportNum() != null
                    && params.getPassNum() != null
                    && existingReport.getReportNum().compareTo(params.getPassNum()) == 0
                    && Objects.equals(StrUtil.nullToEmpty(existingSubmitted).trim(),
                    StrUtil.nullToEmpty(params.getTransferCode()).trim())) {
                return;
            }
            throw new CheckedException("报工请求号已被其他业务使用，请刷新后重新提交");
        }

        ReportModeEnum reportMode = ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType());
        identifierMappingService.resolve(arrange, task, reportMode, params);
        executionTemplateService.validateReport(arrange, params, channel);
        reworkMaterialPlans.assertReady(task);
        prepareReportContext(params, arrange, task, reportMode, channel);
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
        params.setQualityGateMode(gate.getGateMode());
        params.setQualityGateStatus("REWORK".equals(task.getResourceType()) ? "PENDING"
                : ("WARN".equals(gate.getGateMode()) && gate.getMessage().startsWith("质量门预警")
                ? "WARN" : ("NONE".equals(gate.getGateMode()) ? "NONE" : "PASSED")));
        params.setQualityGateMessage(gate.getMessage());
        MesQualityEvidenceSupport.Evidence qualityEvidence = MesQualityEvidenceSupport.capture(gate);
        params.setQualityTaskIds(qualityEvidence.taskIdsJson());
        params.setQualityEvidenceFingerprint(qualityEvidence.fingerprint());
        params.setQualityEvidenceSnapshot(qualityEvidence.snapshotJson());
        boolean qualityReleased = Boolean.TRUE.equals(gate.getPassed());
        params.setQualityQuantityStatus(qualityReleased
                ? ("NONE".equals(gate.getGateMode()) || gate.getItems() == null || gate.getItems().isEmpty()
                ? QualityQuantityStatusEnum.NOT_REQUIRED.name()
                : QualityQuantityStatusEnum.RELEASED.name())
                : QualityQuantityStatusEnum.PENDING.name());
        params.setQualityReleasedNum(qualityReleased ? params.getPassNum() : BigDecimal.ZERO);
        params.setQualityPendingNum(qualityReleased ? BigDecimal.ZERO : params.getPassNum());
        params.setQualityRejectedNum(BigDecimal.ZERO);

        // 校验产出物料可用性
        MdmMaterialEntity mat = matService.getDetails(params.getMatCode()).getData();
        if (Objects.isNull(mat) || Objects.equals(mat.getStatus(), 0)) {
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
        this.save(report);
        identifierMappingService.recordSuccessfulUse(report, params);
        reportExtensionChain.afterPersist(extensionContext, report);
        processOperationFactory.applyInventory(reportMode.getCode(), params);
        // 保存操作记录
        MesTaskReportRecordEntity record = new MesTaskReportRecordEntity();
        BeanUtils.copyProperties(report, record);
        record.setReportId(report.getId());
        record.setType(ReportRecordTypeEnum.REPORT.name());
        record.setTriggerType(report.getType());
        record.setContent(reportResult.getReportMessage());
        record.setFactoryCode(arrange.getFactoryCode());
        recordService.save(record);

        // 包装装箱单独记录一份操作记录
        if (StrUtil.isNotBlank(reportResult.getWrapMessage())) {
            MesTaskReportRecordEntity wrapRecord = new MesTaskReportRecordEntity();
            BeanUtils.copyProperties(report, wrapRecord);
            wrapRecord.setReportId(report.getId());
            wrapRecord.setType(ReportRecordTypeEnum.WRAP.name());
            wrapRecord.setTriggerType(report.getType());
            wrapRecord.setContent(reportResult.getWrapMessage());
            wrapRecord.setFactoryCode(arrange.getFactoryCode());
            recordService.save(wrapRecord);
        }

        // 乐观条件更新，防止多人或设备并发报工造成超报和数量覆盖。
        updateArrangeCompletedQuantity(arrange, report.getReportNum(), report.getQualityReleasedNum());

        // 更新生产工单状态
        updateTask(arrange, task, report);

        // 成功过站履历必须和报工、物料消耗、产出及数量回写原子提交。
        stationPassService.recordAccepted(params, arrange, task, reportMode, report);
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

    private void updateArrangeCompletedQuantity(MesTaskArrangeEntity arrange, BigDecimal reportNum,
                                                BigDecimal releasedNum) {
        BigDecimal before = arrange.getCompleteNum();
        BigDecimal after = Objects.requireNonNullElse(before, BigDecimal.ZERO).add(reportNum);
        LambdaUpdateWrapper<MesTaskArrangeEntity> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(MesTaskArrangeEntity::getId, arrange.getId());
        if (before == null) {
            wrapper.isNull(MesTaskArrangeEntity::getCompleteNum);
        } else {
            wrapper.eq(MesTaskArrangeEntity::getCompleteNum, before);
        }
        BigDecimal releasedBefore = Objects.requireNonNullElse(arrange.getQualityReleasedNum(), BigDecimal.ZERO);
        boolean updated = arrangeService.update(wrapper.set(MesTaskArrangeEntity::getCompleteNum, after)
                .set(MesTaskArrangeEntity::getQualityReleasedNum, releasedBefore.add(
                        Objects.requireNonNullElse(releasedNum, BigDecimal.ZERO))));
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
    private void updateTask(MesTaskArrangeEntity arrange, MesTaskEntity task, MesTaskReportEntity report) {
        MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>().eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode()));
        if (produceTask == null) {
            throw new CheckedException("未找到派工任务对应的工序任务");
        }
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
            BigDecimal stockBefore = task.getUnInStockNum();
            BigDecimal stockAfter = Objects.requireNonNullElse(stockBefore, BigDecimal.ZERO)
                    .add(Objects.requireNonNullElse(report.getQualityReleasedNum(), BigDecimal.ZERO));
            LambdaUpdateWrapper<MesTaskEntity> taskWrapper = Wrappers.lambdaUpdate();
            taskWrapper.eq(MesTaskEntity::getId, task.getId());
            if (stockBefore == null) {
                taskWrapper.isNull(MesTaskEntity::getUnInStockNum);
            } else {
                taskWrapper.eq(MesTaskEntity::getUnInStockNum, stockBefore);
            }
            if (!taskService.update(taskWrapper.set(MesTaskEntity::getUnInStockNum, stockAfter))) {
                throw new CheckedException("工单待入库数量已被其他操作更新，请刷新任务后重试");
            }
        }
    }

    /**
     * 生产报工-不合格品上报
     *
     * @param params
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
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
        MesTaskArrangeEntity arrange = arrangeService.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getCode, params.getArrangeCode()));

        if (Objects.isNull(arrange)) {
            throw new CheckedException("未找到派工任务");
        }
        if (!MesProduceTaskStatusEnum.PRODUCT.name().equals(arrange.getStatus())) {
            throw new CheckedException("生产任务未在进行中，请先对任务开工");
        }

        ReportModeEnum reportMode = ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType());
        if ((reportMode == ReportModeEnum.ONE_CODE
                || reportMode == ReportModeEnum.PACKAGE_ONE_CODE
                || reportMode == ReportModeEnum.TRANSFER_CARD)
                && StrUtil.isBlank(params.getTransferCode())) {
            throw new CheckedException("当前报工模式必须提供条码或流转卡");
        }
        params.setRequestId(normalizeRequestId(params.getRequestId()));
        MesTaskReportUnqualifiedEntity existing = unqualifiedService.getOne(
                new LambdaQueryWrapper<MesTaskReportUnqualifiedEntity>()
                        .eq(MesTaskReportUnqualifiedEntity::getRequestId, params.getRequestId()));
        if (existing != null) {
            if (Objects.equals(existing.getArrangeCode(), arrange.getCode())
                    && existing.getUnqualifiedNum() != null
                    && existing.getUnqualifiedNum().compareTo(params.getUnPassNum()) == 0
                    && Objects.equals(existing.getType(), params.getUnqualifiedType())
                    && Objects.equals(StrUtil.nullToEmpty(existing.getTransferCode()).trim(),
                    StrUtil.nullToEmpty(params.getTransferCode()).trim())) {
                return;
            }
            throw new CheckedException("不合格上报请求号已被其他业务使用，请刷新后重新提交");
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
        unqualified.setTransferCode(StrUtil.blankToDefault(params.getTransferCode(), null));
        unqualified.setWorkerCode(currentUser.getUsername());
        unqualified.setWorkerName(currentUser.getName());
        unqualified.setFactoryCode(arrange.getFactoryCode());
        unqualifiedService.save(unqualified);

        // 保存操作记录
        MesTaskReportRecordEntity record = new MesTaskReportRecordEntity();
        BeanUtils.copyProperties(unqualified, record);
        record.setType(ReportRecordTypeEnum.UNQUALIFIED.name());
        record.setContent(currentUser.getName() + "于" + DateUtil.format(unqualified.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行报工-不合格品上报，不合格数量为：" + unqualified.getUnqualifiedNum());
        recordService.save(record);

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
        MesTaskEntity task = taskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()));
        if (task == null) {
            throw new CheckedException("未找到派工任务对应的生产工单");
        }
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

    }

    /**
     * 生产报工-投料
     *
     * @param params
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void feed(MesTaskReportFeedVO params) {
        MomUser currentUser = SecurityUtils.getUser();
        // 保存投料信息
        MesTaskReportFeedEntity feed = new MesTaskReportFeedEntity();
        feed.setArrangeCode(params.getArrangeCode());
        feed.setTaskCode(params.getTaskCode());
        feed.setProduceTaskCode(params.getProduceTaskCode());
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
        feed.setFactoryCode(params.getFactoryCode());
        feedReportService.save(feed);

        // 保存操作记录
        MesTaskReportRecordEntity record = new MesTaskReportRecordEntity();
        BeanUtils.copyProperties(feed, record);
        record.setType(ReportRecordTypeEnum.FEED.name());
        record.setContent(currentUser.getName() + "于" + DateUtil.format(feed.getReportTime(), "yyyy-MM-dd HH:mm:ss") + "进行报工-投料，投料数量为：" + feed.getFeedNum());
        recordService.save(record);

    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleFeedEvent(FeedRecordSaveEvent event) {
        log.debug("投料记录保存");
        MesTaskReportFeedVO params = event.getVo();
        this.feed(params);
    }

}
