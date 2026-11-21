package com.kitex.mom.mes.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteBomService;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.batch.BatchGeneratorService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.mes.support.MesPatrolInterval;
import com.kitex.mom.mes.vo.MesTaskReportOmsVO;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.oms.enums.OrderLongStateEnum;
import com.kitex.mom.plan.feign.RemotePlnIssueService;
import com.kitex.mom.qms.api.feign.RemoteProcedureService;
import com.kitex.mom.qms.api.feign.RemoteQualityStandardService;
import com.kitex.mom.qms.dto.QmsProcedureSourceWithdrawalDTO;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import com.kitex.mom.qms.enums.InspectionMethodEnum;
import com.kitex.mom.qms.enums.InspectionTaskTypeEnum;
import com.kitex.mom.qms.enums.ProcedureLabelTypeEnum;
import com.kitex.mom.wms.api.feign.RemoteProductInNoticeService;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 生产工单接单、工程冻结下达、草稿维护及计划/质量/仓储衔接。
 *
 * @author kitex
 * @since 2025-05-06
 */
@Service
@AllArgsConstructor
public class MesTaskServiceImpl extends ServiceImpl<MesTaskMapper, MesTaskEntity> implements MesTaskService {

    private final RemoteSeqConfigService remoteSeqConfigService;
    private final RemoteBomService bomService;
    private final MesProduceTaskService produceTaskService;
    private final RemotePlnIssueService remotePlnIssueService;
    private final RemoteMaterialService materialService;
    private final MesInspectionRuleService inspectionRuleService;
    private final RemoteProcedureService remoteProcedureService;
    private final MesQmsInspectionCommandService qmsInspectionCommands;
    private final MesInspectionGenerationDispatcher inspectionGeneration;
    private final RemoteQualityStandardService remoteQualityStandardService;
    private final RemoteParamService remoteParamService;
    private final RemoteClassesService remoteClassesService;
    private final MesInspectionGenRecordService genRecordService;
    private final MesTaskReportMapper mesTaskReportMapper;
    private final RemoteProductInNoticeService inNoticeService;
    private final RemoteMaterialService matService;
    private final MesTaskArrangeMapper mesTaskArrangeMapper;
    private final MesReworkCompletionCoordinator reworkCompletions;
    private final MesReworkMaterialPlanService reworkMaterialPlans;
    private final MesQualityQuantityService qualityQuantityService;
    private final MesApsBatchAllocationMapper apsBatchAllocationMapper;
    private final MesApsSourceQuantityEventMapper apsSourceQuantityEventMapper;
    private final MesTaskInWmsReceiptMapper taskInWmsReceiptMapper;
    private final MesTaskInWmsCommandService taskInWmsCommands;
    private final MesTaskInWmsReceiptDetailMapper taskInWmsReceiptDetailMapper;
    private final MesInboundAllocationService inboundAllocationService;
    private final MesPlanWithdrawalReceiptMapper planWithdrawalReceiptMapper;
    private final MesWarehousePickingBillMapper warehousePickingBillMapper;
    private final MesWarehouseFeedMapper warehouseFeedMapper;
    private final MesApsSourceQuantityService sourceQuantityService;
    private final MesScrapMaterialService scrapMaterialService;
    private final MesToolingExecutionMapper toolingExecutionMapper;
    private final MesTaskExecutionSegmentMapper taskExecutionSegmentMapper;
    private final MesChangeoverExecutionMapper changeoverExecutionMapper;
    private final MesDispatchReturnService dispatchReturnService;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesProcessParameterStandardFreezeService parameterStandardFreezeService;
    private final MesSopFreezeService sopFreezeService;
    private final MesExecutionTemplateService executionTemplateService;
    private final MesIdentifierBindingGroupService bindingGroups;
    private final MesTerminalExecutionGuard terminalExecutionGuard;
    private final MesEngineeringPackageService engineeringPackages;
    private final MesFormalPlanReceiptVerifier formalPlanReceiptVerifier;
    private final MesManualTaskService manualTasks;
    private final MesProductionOrderService productionOrderService;
    private final MesPlanCompletionOutboxService planCompletionOutbox;
	private final MesTaskCommandGuard taskCommands;
	private final BatchGeneratorService batchGeneratorService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesPlanWithdrawalDTO.Receipt withdrawPlan(MesPlanWithdrawalDTO.Command command) {
        if (command == null || StrUtil.isBlank(command.getRequestId()) || command.getIssueId() == null
                || StrUtil.isBlank(command.getIssueNo()) || command.getExpectedQuantity() == null
                || command.getExpectedQuantity().signum() <= 0 || StrUtil.isBlank(command.getFactoryCode())
                || !Boolean.TRUE.equals(command.getPreview()) && StrUtil.isBlank(command.getExpectedEvidenceFingerprint())
                || StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("MES计划撤回必须提供请求号、计划、数量、工厂、证据指纹和原因");
        }
        String requestId = command.getRequestId().trim();
        if (requestId.length() > 64 || command.getIssueNo().trim().length() > 100
                || !Boolean.TRUE.equals(command.getPreview()) && command.getExpectedEvidenceFingerprint().trim().length() != 64
                || command.getReason().trim().length() > 500) {
            throw new CheckedException("MES计划撤回请求号/计划号/证据指纹/原因长度不合法");
        }
        String actualFactory = factoryAccess.require("write");
        if (!Objects.equals(actualFactory, command.getFactoryCode().trim())) {
            throw new CheckedException("计划撤回工厂与实际授权工厂不一致");
        }
        String requestFingerprint = null;
        if (!Boolean.TRUE.equals(command.getPreview())) {
            requestFingerprint = sha256(String.join("|", String.valueOf(command.getIssueId()),
                    command.getIssueNo().trim(), command.getExpectedQuantity().stripTrailingZeros().toPlainString(),
                    command.getFactoryCode().trim(), command.getExpectedEvidenceFingerprint().trim(),
                    command.getReason().trim()));
            planWithdrawalReceiptMapper.lockKey("REQUEST:" + requestId);
            MesPlanWithdrawalReceiptEntity replay = planWithdrawalReceiptMapper.selectOne(
                    new LambdaQueryWrapper<MesPlanWithdrawalReceiptEntity>()
                            .eq(MesPlanWithdrawalReceiptEntity::getTenantId, com.kitex.common.data.tenant.TenantContextHolder.getTenantId())
                            .eq(MesPlanWithdrawalReceiptEntity::getFactoryCode, actualFactory)
                            .eq(MesPlanWithdrawalReceiptEntity::getRequestId, requestId));
            if (replay != null) {
                if (!Objects.equals(replay.getRequestFingerprint(), requestFingerprint)) {
                    throw new CheckedException("MES计划撤回幂等号已被不同请求使用");
                }
                return withdrawalReceipt(replay);
            }
        }

        planWithdrawalReceiptMapper.lockKey("ISSUE:" + command.getFactoryCode().trim() + ':' + command.getIssueId());
        List<MesApsBatchAllocationEntity> sourceRows = apsBatchAllocationMapper.selectList(
                new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                        .eq(MesApsBatchAllocationEntity::getTenantId, com.kitex.common.data.tenant.TenantContextHolder.getTenantId())
                        .eq(MesApsBatchAllocationEntity::getIssueId, command.getIssueId())
                        .eq(MesApsBatchAllocationEntity::getIssueNo, command.getIssueNo().trim())
                        .eq(MesApsBatchAllocationEntity::getFactoryCode, command.getFactoryCode().trim())
                        .orderByAsc(MesApsBatchAllocationEntity::getMesTaskId)
                        .orderByAsc(MesApsBatchAllocationEntity::getId));
        if (sourceRows.isEmpty()) {
            throw new CheckedException("MES没有该PLAN计划的完整APS来源分配账；历史planNo映射禁止执行写操作");
        }
        BigDecimal allocated = sourceRows.stream().map(MesApsBatchAllocationEntity::getAllocatedQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocated.compareTo(command.getExpectedQuantity()) != 0) {
            throw new CheckedException("MES来源分配数量与PLAN补产数量不守恒，禁止撤回");
        }
        List<Long> taskIds = sourceRows.stream().map(MesApsBatchAllocationEntity::getMesTaskId)
                .filter(Objects::nonNull).distinct().sorted().toList();
        List<MesTaskEntity> observedTasks = taskIds.isEmpty() ? List.of() : list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, com.kitex.common.data.tenant.TenantContextHolder.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, actualFactory).in(MesTaskEntity::getId, taskIds));
        if (observedTasks.size() != taskIds.size()) throw new CheckedException("当前租户工厂的来源工单归属不完整");
        // 多工单先按编码统一取业务锁，之后才按ID锁行，避免不同入口相反顺序等待。
        observedTasks.stream().map(MesTaskEntity::getCode).distinct().sorted().forEach(bindingGroups::lockDispatchTask);
        List<MesTaskEntity> tasks = taskIds.isEmpty() ? List.of() : list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, com.kitex.common.data.tenant.TenantContextHolder.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, actualFactory)
                .in(MesTaskEntity::getId, taskIds).orderByAsc(MesTaskEntity::getId).last("FOR UPDATE"));
        if (tasks.size() != taskIds.size()) throw new CheckedException("MES来源分配账关联工单缺失，禁止撤回");
        for (MesTaskEntity locked : tasks) {
            if (observedTasks.stream().noneMatch(old -> Objects.equals(old.getId(), locked.getId())
                    && Objects.equals(old.getCode(), locked.getCode()))) {
                throw new CheckedException("来源工单身份已变化，请原号重核，禁止在持有行锁后改锁目标");
            }
        }
        List<String> taskCodes = tasks.stream().map(MesTaskEntity::getCode).sorted().toList();
        List<MesProduceTaskEntity> produceTasks = taskCodes.isEmpty() ? List.of() : produceTaskService.list(
                new LambdaQueryWrapper<MesProduceTaskEntity>().in(MesProduceTaskEntity::getTaskCode, taskCodes)
                        .orderByAsc(MesProduceTaskEntity::getTaskCode).orderByAsc(MesProduceTaskEntity::getId)
                        .last("FOR UPDATE"));
        List<String> produceTaskCodes = produceTasks.stream().map(MesProduceTaskEntity::getCode).sorted().toList();
        List<MesTaskArrangeEntity> arranges = taskCodes.isEmpty() ? List.of() : mesTaskArrangeMapper.selectList(
                new LambdaQueryWrapper<MesTaskArrangeEntity>().in(MesTaskArrangeEntity::getTaskCode, taskCodes)
                        .orderByAsc(MesTaskArrangeEntity::getTaskCode).orderByAsc(MesTaskArrangeEntity::getId)
                        .last("FOR UPDATE"));
        arranges.forEach(terminalExecutionGuard::assertUnoccupied);
        List<MesInspectionGenRecordEntity> genRecords = taskCodes.isEmpty() ? List.of() : genRecordService.list(
                new LambdaQueryWrapper<MesInspectionGenRecordEntity>().in(MesInspectionGenRecordEntity::getTaskCode, taskCodes)
                        .orderByAsc(MesInspectionGenRecordEntity::getTaskCode)
                        .orderByAsc(MesInspectionGenRecordEntity::getId).last("FOR UPDATE"));
        List<Long> qmsTaskIds = genRecords.stream().map(MesInspectionGenRecordEntity::getInspectionId)
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (genRecords.stream().anyMatch(record -> "1".equals(record.getGenFlag()) && record.getInspectionId() == null)) {
            throw new CheckedException("MES质检生成账标记成功但缺少QMS任务ID，禁止撤回");
        }
        for (MesTaskEntity task : tasks) {
            if (!Objects.equals(task.getFactoryCode(), command.getFactoryCode().trim())) {
                throw new CheckedException("MES来源分配账存在跨工厂工单，禁止撤回");
            }
            List<MesApsBatchAllocationEntity> all = allocationRows(task);
            validateAllocationRows(task, all);
            if (all.size() != 1 || !Objects.equals(all.get(0).getIssueId(), command.getIssueId())) {
                throw new CheckedException("MES工单[" + task.getCode() + "]为合批或含其他PLAN来源，必须先走整批拆解/重排，禁止局部撤回");
            }
            if (!Set.of(MesTaskStatusEnum.CREATE.name(), MesTaskStatusEnum.ISSUE.name(),
                    MesTaskStatusEnum.DISPATCH.name()).contains(task.getStatus())) {
                throw new CheckedException("MES工单[" + task.getCode() + "]状态为" + task.getStatus()
                        + "；仅新建、已下达、待接收或已接收但尚未开工且无现场事实的工单允许计划级撤回");
            }
            boolean quantityFact = positive(task.getCompleteNum()) || positive(task.getInStockNum())
                    || positive(task.getUnInStockNum()) || positive(task.getUnPassNum());
            boolean reportFact = mesTaskReportMapper.selectCount(new LambdaQueryWrapper<MesTaskReportEntity>()
                    .eq(MesTaskReportEntity::getTaskCode, task.getCode())) > 0;
            boolean inStockFact = taskInWmsReceiptMapper.selectCount(new LambdaQueryWrapper<MesTaskInWmsReceiptEntity>()
                    .eq(MesTaskInWmsReceiptEntity::getMesTaskId, task.getId())) > 0;
            boolean sourceQuantityFact = apsSourceQuantityEventMapper.selectCount(
                    new LambdaQueryWrapper<MesApsSourceQuantityEventEntity>()
                            .eq(MesApsSourceQuantityEventEntity::getMesTaskId, task.getId())) > 0;
            boolean pickingFact = warehousePickingBillMapper.selectCount(
                    new LambdaQueryWrapper<MesWarehousePickingBillEntity>()
                            .eq(MesWarehousePickingBillEntity::getTaskCode, task.getCode())) > 0;
            boolean feedingFact = warehouseFeedMapper.selectCount(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                    .eq(MesWarehouseFeedEntity::getTaskCode, task.getCode())) > 0;
            if (task.getStartTime() != null || task.getFinishTime() != null || quantityFact
                    || reportFact || inStockFact || sourceQuantityFact
                    || pickingFact || feedingFact) {
                throw new CheckedException("MES工单[" + task.getCode()
                        + "]已存在报工、质量数量、领投料或入库事实，必须走现场停止/退料/冲销闭环");
            }
        }
        for (MesProduceTaskEntity produceTask : produceTasks) {
            if (!Set.of(MesProduceTaskStatusEnum.WAIT.name(), MesProduceTaskStatusEnum.UN_RECEIVE.name(),
                            MesProduceTaskStatusEnum.RECEIVE.name())
                    .contains(produceTask.getStatus()) || produceTask.getStartTime() != null || produceTask.getFinishTime() != null
                    || positive(produceTask.getCompleteNum()) || positive(produceTask.getQualityReleasedNum())
                    || positive(produceTask.getUnPassNum())) {
                throw new CheckedException("MES工序任务[" + produceTask.getCode()
                        + "]已开工、完工或形成质量数量，必须进入现场停止/冲销闭环");
            }
        }
        Set<String> produceCodeSet = new HashSet<>(produceTaskCodes);
        for (MesTaskArrangeEntity arrange : arranges) {
            if (!produceCodeSet.contains(arrange.getProduceTaskCode())) {
                throw new CheckedException("MES派工任务[" + arrange.getCode() + "]没有精确工序任务来源");
            }
            if (!Set.of(MesProduceTaskStatusEnum.UN_RECEIVE.name(), MesProduceTaskStatusEnum.RECEIVE.name())
                    .contains(arrange.getStatus())
                    || arrange.getStartTime() != null || arrange.getFinishTime() != null
                    || positive(arrange.getCompleteNum()) || positive(arrange.getQualityReleasedNum())
                    || positive(arrange.getUnPassNum())
                    || MesProduceTaskStatusEnum.UN_RECEIVE.name().equals(arrange.getStatus())
                    && StrUtil.isNotBlank(arrange.getActualWorksiteCode())
                    || MesProduceTaskStatusEnum.RECEIVE.name().equals(arrange.getStatus())
                    && StrUtil.isBlank(arrange.getActualWorksiteCode())) {
                throw new CheckedException("MES派工任务[" + arrange.getCode()
                        + "]接收状态、实际工位或执行数量不一致，必须进入现场核查/冲销闭环");
            }
        }
        long executionSegmentCount = taskCodes.isEmpty() ? 0 : taskExecutionSegmentMapper.selectCount(
                new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                        .in(MesTaskExecutionSegmentEntity::getTaskCode, taskCodes));
        if (executionSegmentCount > 0) throw new CheckedException("MES工单已有现场执行时间段，不能按开工前计划撤回");
        List<MesChangeoverExecutionEntity> activeChangeovers = new ArrayList<>();
        for (MesTaskArrangeEntity arrange : arranges) {
            if (!MesProduceTaskStatusEnum.RECEIVE.name().equals(arrange.getStatus())
                    || StrUtil.isBlank(arrange.getPlannedDeviceCode())
                    || StrUtil.isBlank(arrange.getActualWorksiteCode())) {
                continue;
            }
            List<MesChangeoverExecutionEntity> rows = changeoverExecutionMapper.selectList(
                    new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
                            .eq(MesChangeoverExecutionEntity::getFactoryCode, arrange.getFactoryCode())
                            .eq(MesChangeoverExecutionEntity::getDeviceCode, arrange.getPlannedDeviceCode())
                            .eq(MesChangeoverExecutionEntity::getWorksiteCode, arrange.getActualWorksiteCode())
                            .eq(MesChangeoverExecutionEntity::getStatus, "STARTED")
                            .orderByAsc(MesChangeoverExecutionEntity::getId).last("FOR UPDATE"));
            if (!rows.isEmpty()) {
                throw new CheckedException("MES派工任务[" + arrange.getCode()
                        + "]实际工位与设备存在进行中的换型，必须先完成或取消换型");
            }
            activeChangeovers.addAll(rows);
        }
        List<MesToolingExecutionEntity> toolingExecutions = taskCodes.isEmpty() ? List.of()
                : toolingExecutionMapper.selectList(new LambdaQueryWrapper<MesToolingExecutionEntity>()
                .in(MesToolingExecutionEntity::getTaskCode, taskCodes)
                .orderByAsc(MesToolingExecutionEntity::getTaskCode)
                .orderByAsc(MesToolingExecutionEntity::getId).last("FOR UPDATE"));
        for (MesToolingExecutionEntity tooling : toolingExecutions) {
            if (!Set.of("CHECKED_IN", "RELEASED").contains(tooling.getStatus())
                    || tooling.getStartTime() != null || tooling.getActualLifeUsage() != null
                    || StrUtil.isNotBlank(tooling.getLifeConsumptionRequestId())) {
                throw new CheckedException("MES工装[" + tooling.getToolingCode()
                        + "]已投入生产或完成寿命结算，不能随计划直接撤回");
            }
        }

        String qmsEvidence = sha256("NOT_APPLICABLE");
        QmsProcedureSourceWithdrawalDTO.Receipt qmsPreview = null;
        if (!produceTaskCodes.isEmpty()) {
            QmsProcedureSourceWithdrawalDTO.Command qmsCommand = qmsWithdrawalCommand(command, produceTaskCodes,
                    qmsTaskIds, true, null);
            R<QmsProcedureSourceWithdrawalDTO.Receipt> qmsResponse = remoteProcedureService.withdrawByMesSource(qmsCommand);
            qmsPreview = qmsResponse == null ? null : qmsResponse.getData();
            if (qmsResponse == null || !qmsResponse.isOk() || qmsPreview == null
                    || !Set.of("ELIGIBLE", "ALREADY_WITHDRAWN").contains(qmsPreview.getStatus())
                    || StrUtil.isBlank(qmsPreview.getEvidenceFingerprint())) {
                throw new CheckedException("QMS工序质检撤回资格校验失败："
                        + (qmsResponse == null ? "无响应" : qmsResponse.getMsg()));
            }
            qmsEvidence = qmsPreview.getEvidenceFingerprint();
        }
        String actualEvidence = withdrawalEvidence(tasks, sourceRows, produceTasks, arranges, genRecords,
                toolingExecutions, activeChangeovers, qmsEvidence);
        if (Boolean.TRUE.equals(command.getPreview())) {
            return new MesPlanWithdrawalDTO.Receipt().setRequestId(requestId).setIssueId(command.getIssueId())
                    .setIssueNo(command.getIssueNo().trim()).setStatus("ELIGIBLE")
                    .setClosedTaskCount(0).setClosedTaskCodes(List.of()).setClosedProduceTaskCount(0)
                    .setClosedArrangeCount(0).setCancelledQmsTaskCount(0).setQmsEvidenceFingerprint(qmsEvidence)
                    .setEvidenceFingerprint(actualEvidence).setHandledTime(LocalDateTime.now())
                    .setMessage("MES工单及其待接收/已接收未开工派工、未投产工装和未开始质检均无执行事实，允许提交撤回审批");
        }
        if (!Objects.equals(actualEvidence, command.getExpectedEvidenceFingerprint().trim())) {
            throw new CheckedException("MES计划撤回证据已变化，请刷新补产需求后重新申请");
        }
        dispatchReturnService.supersedeByPlan(arranges, requestId);
        int cancelledQmsTaskCount = 0;
        if (!produceTaskCodes.isEmpty()) {
            QmsProcedureSourceWithdrawalDTO.Command qmsCommand = qmsWithdrawalCommand(command, produceTaskCodes,
                    qmsTaskIds, false, qmsEvidence);
            R<QmsProcedureSourceWithdrawalDTO.Receipt> qmsResponse = remoteProcedureService.withdrawByMesSource(qmsCommand);
            QmsProcedureSourceWithdrawalDTO.Receipt qmsReceipt = qmsResponse == null ? null : qmsResponse.getData();
            if (qmsResponse == null || !qmsResponse.isOk() || qmsReceipt == null
                    || !Set.of("SUCCEEDED", "ALREADY_WITHDRAWN").contains(qmsReceipt.getStatus())
                    || !Objects.equals(qmsReceipt.getEvidenceFingerprint(), qmsEvidence)) {
                throw new CheckedException("QMS工序质检任务级联作废失败："
                        + (qmsResponse == null ? "无响应" : qmsResponse.getMsg()));
            }
            cancelledQmsTaskCount = Optional.ofNullable(qmsReceipt.getCancelledTaskCount()).orElse(0);
        }
        LocalDateTime handledTime = LocalDateTime.now();
        for (MesToolingExecutionEntity tooling : toolingExecutions) {
            if ("CHECKED_IN".equals(tooling.getStatus())) {
                tooling.setStatus("RELEASED");
                tooling.setReleaseTime(handledTime);
                if (toolingExecutionMapper.updateById(tooling) != 1) {
                    throw new CheckedException("MES工装[" + tooling.getToolingCode() + "]释放失败");
                }
            }
        }
        for (MesTaskArrangeEntity arrange : arranges) {
            arrange.setStatus(MesProduceTaskStatusEnum.CLOSE.name());
            if (mesTaskArrangeMapper.updateById(arrange) != 1) {
                throw new CheckedException("MES派工任务[" + arrange.getCode() + "]关闭失败");
            }
        }
        for (MesProduceTaskEntity produceTask : produceTasks) {
            produceTask.setStatus(MesProduceTaskStatusEnum.CLOSE.name());
            if (!produceTaskService.updateById(produceTask)) {
                throw new CheckedException("MES工序任务[" + produceTask.getCode() + "]关闭失败");
            }
        }
        for (MesInspectionGenRecordEntity record : genRecords) {
            record.setGateStatus(QualityGateStatusEnum.CANCELED.name()).setInspectionStatus("CANCELED")
                    .setSyncMessage("来源MES计划已受控撤回").setLastSyncTime(handledTime).setNextTime(null).setNextShift(null);
            if (!genRecordService.updateById(record)) {
                throw new CheckedException("MES质检生成记录[" + record.getId() + "]关闭失败");
            }
        }
        for (MesTaskEntity task : tasks) {
            task.setStatus(MesTaskStatusEnum.CLOSE.name());
            if (!updateById(task)) throw new CheckedException("MES工单[" + task.getCode() + "]关闭失败");
        }
        String codes = tasks.stream().map(MesTaskEntity::getCode).collect(Collectors.joining(","));
        MesPlanWithdrawalReceiptEntity receipt = new MesPlanWithdrawalReceiptEntity()
                .setRequestId(requestId).setRequestFingerprint(requestFingerprint)
                .setIssueId(command.getIssueId()).setIssueNo(command.getIssueNo().trim())
                .setExpectedQuantity(command.getExpectedQuantity()).setExpectedEvidenceFingerprint(actualEvidence)
                .setActualEvidenceFingerprint(actualEvidence).setClosedTaskCount(tasks.size())
                .setClosedTaskCodes(codes).setClosedProduceTaskCount(produceTasks.size())
                .setClosedArrangeCount(arranges.size()).setCancelledQmsTaskCount(cancelledQmsTaskCount)
                .setQmsEvidenceFingerprint(qmsEvidence).setStatus("SUCCEEDED").setReason(command.getReason().trim())
                .setMessage("已关闭" + tasks.size() + "张MES工单、" + produceTasks.size() + "张工序任务、"
                        + arranges.size() + "张待接收/已接收未开工派工，释放未投产工装，并作废"
                        + cancelledQmsTaskCount + "张QMS质检任务")
                .setHandledTime(handledTime).setFactoryCode(command.getFactoryCode().trim());
        if (planWithdrawalReceiptMapper.insert(receipt) != 1) throw new CheckedException("MES计划撤回回执保存失败");
        return withdrawalReceipt(receipt);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private String withdrawalEvidence(List<MesTaskEntity> tasks, List<MesApsBatchAllocationEntity> rows,
                                      List<MesProduceTaskEntity> produceTasks, List<MesTaskArrangeEntity> arranges,
                                      List<MesInspectionGenRecordEntity> genRecords,
                                      List<MesToolingExecutionEntity> toolingExecutions,
                                      List<MesChangeoverExecutionEntity> activeChangeovers, String qmsEvidence) {
        String taskFacts = tasks.stream().map(task -> String.join("~", String.valueOf(task.getId()),
                        Objects.toString(task.getCode(), ""), Objects.toString(task.getStatus(), ""),
                        Objects.toString(task.getApsBatchNo(), ""), Objects.toString(task.getNoticeNum(), ""),
                        Objects.toString(task.getAlreadyNoticeNum(), ""), Objects.toString(task.getCompleteNum(), ""),
                        Objects.toString(task.getInStockNum(), ""), Objects.toString(task.getUnInStockNum(), ""),
                        Objects.toString(task.getUnPassNum(), ""), Objects.toString(task.getStartTime(), ""),
                        Objects.toString(task.getFinishTime(), "")))
                .collect(Collectors.joining(";"));
        String allocations = rows.stream().map(row -> String.join("~", String.valueOf(row.getId()),
                        Objects.toString(row.getMesTaskId(), ""), Objects.toString(row.getApsBatchNo(), ""),
                        Objects.toString(row.getIssueId(), ""), Objects.toString(row.getIssueNo(), ""),
                        Objects.toString(row.getAllocatedQuantity(), ""), Objects.toString(row.getCompletedQuantity(), ""),
                        Objects.toString(row.getInStockQuantity(), "")))
                .collect(Collectors.joining(";"));
        String produceFacts = produceTasks.stream().map(task -> String.join("~", String.valueOf(task.getId()),
                        Objects.toString(task.getCode(), ""), Objects.toString(task.getTaskCode(), ""),
                        Objects.toString(task.getStatus(), ""), Objects.toString(task.getNoticeNum(), ""),
                        Objects.toString(task.getCompleteNum(), ""), Objects.toString(task.getQualityReleasedNum(), ""),
                        Objects.toString(task.getUnPassNum(), ""), Objects.toString(task.getStartTime(), ""),
                        Objects.toString(task.getFinishTime(), "")))
                .collect(Collectors.joining(";"));
        String arrangeFacts = arranges.stream().map(task -> String.join("~", String.valueOf(task.getId()),
                        Objects.toString(task.getCode(), ""), Objects.toString(task.getProduceTaskCode(), ""),
                        Objects.toString(task.getStatus(), ""), Objects.toString(task.getWorkerCode(), ""),
                        Objects.toString(task.getActualWorksiteCode(), ""), Objects.toString(task.getCompleteNum(), ""),
                        Objects.toString(task.getQualityReleasedNum(), ""), Objects.toString(task.getUnPassNum(), ""),
                        Objects.toString(task.getStartTime(), ""), Objects.toString(task.getFinishTime(), "")))
                .collect(Collectors.joining(";"));
        String qmsGenerationFacts = genRecords.stream().map(record -> String.join("~", String.valueOf(record.getId()),
                        Objects.toString(record.getProduceTaskCode(), ""), Objects.toString(record.getInspectionId(), ""),
                        Objects.toString(record.getSourceRequestId(), ""), Objects.toString(record.getGenFlag(), ""),
                        Objects.toString(record.getGateStatus(), "")))
                .collect(Collectors.joining(";"));
        String toolingFacts = toolingExecutions.stream().map(tooling -> String.join("~", String.valueOf(tooling.getId()),
                        Objects.toString(tooling.getArrangeCode(), ""), Objects.toString(tooling.getToolingCode(), ""),
                        Objects.toString(tooling.getStatus(), ""), Objects.toString(tooling.getStartTime(), ""),
                        Objects.toString(tooling.getActualLifeUsage(), ""),
                        Objects.toString(tooling.getLifeConsumptionRequestId(), "")))
                .collect(Collectors.joining(";"));
        String changeoverFacts = activeChangeovers.stream().map(changeover -> String.join("~",
                        String.valueOf(changeover.getId()), Objects.toString(changeover.getStatus(), ""),
                        Objects.toString(changeover.getDeviceCode(), ""),
                        Objects.toString(changeover.getWorksiteCode(), ""),
                        Objects.toString(changeover.getStartedTime(), "")))
                .collect(Collectors.joining(";"));
        return sha256(taskFacts + '|' + allocations + '|' + produceFacts + '|' + arrangeFacts + '|'
                + qmsGenerationFacts + '|' + toolingFacts + '|' + changeoverFacts + '|' + qmsEvidence);
    }

    private QmsProcedureSourceWithdrawalDTO.Command qmsWithdrawalCommand(MesPlanWithdrawalDTO.Command command,
                                                                         List<String> sourceTaskCodes, List<Long> taskIds, boolean preview, String evidenceFingerprint) {
        QmsProcedureSourceWithdrawalDTO.Command qmsCommand = new QmsProcedureSourceWithdrawalDTO.Command();
        qmsCommand.setRequestId(preview ? "QPRE-" + sha256(command.getIssueId() + "|" + String.join(",", sourceTaskCodes)).substring(0, 40)
                : "MESQ-" + sha256(command.getRequestId()).substring(0, 48));
        qmsCommand.setFactoryCode(command.getFactoryCode().trim());
        qmsCommand.setSourceTaskCodes(sourceTaskCodes);
        qmsCommand.setExpectedTaskIds(taskIds);
        qmsCommand.setPreview(preview);
        qmsCommand.setExpectedEvidenceFingerprint(evidenceFingerprint);
        qmsCommand.setReason(command.getReason().trim());
        return qmsCommand;
    }

    private String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("MES计划撤回证据指纹生成失败");
        }
    }

    private MesPlanWithdrawalDTO.Receipt withdrawalReceipt(MesPlanWithdrawalReceiptEntity entity) {
        return new MesPlanWithdrawalDTO.Receipt().setRequestId(entity.getRequestId())
                .setIssueId(entity.getIssueId()).setIssueNo(entity.getIssueNo()).setStatus(entity.getStatus())
                .setClosedTaskCount(entity.getClosedTaskCount())
                .setClosedTaskCodes(StrUtil.isBlank(entity.getClosedTaskCodes()) ? List.of()
                        : Arrays.stream(entity.getClosedTaskCodes().split(",")).toList())
                .setClosedProduceTaskCount(entity.getClosedProduceTaskCount())
                .setClosedArrangeCount(entity.getClosedArrangeCount())
                .setCancelledQmsTaskCount(entity.getCancelledQmsTaskCount())
                .setQmsEvidenceFingerprint(entity.getQmsEvidenceFingerprint())
                .setEvidenceFingerprint(entity.getActualEvidenceFingerprint()).setHandledTime(entity.getHandledTime())
                .setMessage(entity.getMessage());
    }

    @Override
    public MesPlanSourceMappingDTO planSourceMapping(String taskCode, String produceTaskCode, String factoryCode) {
        String requestedTask = trimToNull(taskCode);
        String requestedProduce = trimToNull(produceTaskCode);
        String factory = trimToNull(factoryCode);
        if (factory == null || requestedTask == null && requestedProduce == null) {
            throw new CheckedException("来源计划映射必须提供工厂，以及工单编码或工序任务编码");
        }

        MesProduceTaskEntity produceTask = requestedProduce == null ? null : produceTaskService.getOne(
                new LambdaQueryWrapper<MesProduceTaskEntity>()
                        .eq(MesProduceTaskEntity::getCode, requestedProduce), false);
        if (requestedProduce != null && produceTask == null) {
            throw new CheckedException("MES工序任务不存在：" + requestedProduce);
        }
        if (produceTask != null && !Objects.equals(factory, produceTask.getFactoryCode())) {
            throw new CheckedException("MES工序任务不属于指定工厂");
        }
        String resolvedTaskCode = requestedTask == null ? produceTask.getTaskCode() : requestedTask;
        if (produceTask != null && !Objects.equals(resolvedTaskCode, produceTask.getTaskCode())) {
            throw new CheckedException("MES工单与工序任务归属不一致");
        }
        MesTaskEntity task = getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, resolvedTaskCode), false);
        if (task == null) throw new CheckedException("MES生产工单不存在：" + resolvedTaskCode);
        if (!Objects.equals(factory, task.getFactoryCode())) throw new CheckedException("MES生产工单不属于指定工厂");

        String mappingStatus = "APS_ALLOCATION";
        List<MesApsBatchAllocationEntity> rows = allocationRows(task);
        MesTaskEntity mappedTask = task;
        if (rows.isEmpty() && StrUtil.isNotBlank(task.getSourceTaskCode())) {
            MesTaskEntity source = getOne(new LambdaQueryWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getCode, task.getSourceTaskCode()), false);
            if (source != null && Objects.equals(factory, source.getFactoryCode())) {
                List<MesApsBatchAllocationEntity> sourceRows = allocationRows(source);
                if (!sourceRows.isEmpty()) {
                    mappedTask = source;
                    rows = sourceRows;
                    mappingStatus = "REWORK_SOURCE";
                }
            }
        }

        MesPlanSourceMappingDTO result = new MesPlanSourceMappingDTO()
                .setProductCode(mappedTask.getMatCode())
                .setEngineeringReleaseId(mappedTask.getEngineeringReleaseId() == null ? null
                        : mappedTask.getEngineeringReleaseId().toString())
                .setEngineeringContentHash(mappedTask.getEngineeringContentHash())
                .setRequestedTaskCode(requestedTask).setRequestedProduceTaskCode(requestedProduce)
                .setMesTaskId(mappedTask.getId()).setMesTaskCode(mappedTask.getCode())
                .setProduceTaskId(produceTask == null ? null : produceTask.getId())
                .setProduceTaskCode(produceTask == null ? null : produceTask.getCode())
                .setProcessCode(produceTask == null ? null : produceTask.getRouteCode())
                .setProcessName(produceTask == null ? null : produceTask.getRouteName())
                .setFactoryCode(factory);
        if (!rows.isEmpty()) {
            validateAllocationRows(mappedTask, rows);
            List<MesPlanSourceMappingDTO.Allocation> allocations = rows.stream().map(this::mappingAllocation).toList();
            result.setMappingStatus(mappingStatus).setMappingComplete(true)
                    .setMappingMessage("已按MES保存的APS批次来源分配账解析" + allocations.size() + "张PLAN计划")
                    .setAllocations(allocations);
        } else if (StrUtil.isNotBlank(mappedTask.getPlanNo())) {
            MesPlanSourceMappingDTO.Allocation legacy = new MesPlanSourceMappingDTO.Allocation()
                    .setIssueNo(mappedTask.getPlanNo()).setAllocatedQuantity(mappedTask.getNoticeNum());
            result.setMappingStatus("LEGACY_PLAN_NO").setMappingComplete(false)
                    .setMappingMessage("历史非批次工单仅保存PLAN计划号，消费方必须按工厂复核且禁止直接执行写操作")
                    .setAllocations(List.of(legacy));
        } else {
            result.setMappingStatus("NO_PLAN_SOURCE").setMappingComplete(false)
                    .setMappingMessage("该MES工单没有APS来源分配账或PLAN计划号")
                    .setAllocations(List.of());
        }
        result.setMappingFingerprint(planSourceMappingFingerprint(result));
        return result;
    }

    @Override
    public MesSourceQuantityMappingDTO sourceQuantityMapping(String requestId, String factoryCode) {
        String request = trimToNull(requestId);
        String factory = trimToNull(factoryCode);
        if (request == null || factory == null) throw new CheckedException("来源数量映射必须提供请求号和工厂");
        if (request.length() > 120) throw new CheckedException("来源数量请求号不能超过120位");
        List<MesApsSourceQuantityEventEntity> events = apsSourceQuantityEventMapper.selectList(
                new LambdaQueryWrapper<MesApsSourceQuantityEventEntity>()
                        .eq(MesApsSourceQuantityEventEntity::getRequestId, request)
                        .orderByAsc(MesApsSourceQuantityEventEntity::getSourceIssueNo)
                        .orderByAsc(MesApsSourceQuantityEventEntity::getId));
        MesSourceQuantityMappingDTO result = new MesSourceQuantityMappingDTO().setRequestId(request)
                .setFactoryCode(factory).setAllocations(List.of()).setEventQuantity(BigDecimal.ZERO);
        if (events.isEmpty()) {
            result.setMappingStatus("NO_QUANTITY_EVENT").setMappingComplete(false)
                    .setMappingMessage("MES尚未形成该请求的来源数量分录");
            result.setMappingFingerprint(sourceQuantityMappingFingerprint(result));
            return result;
        }
        MesApsSourceQuantityEventEntity first = events.get(0);
        boolean consistent = events.stream().allMatch(item -> Objects.equals(first.getRequestFingerprint(), item.getRequestFingerprint())
                && Objects.equals(first.getEventKind(), item.getEventKind())
                && Objects.equals(first.getMesTaskId(), item.getMesTaskId())
                && Objects.equals(first.getMesTaskCode(), item.getMesTaskCode())
                && Objects.equals(first.getFactoryCode(), item.getFactoryCode()));
        if (!consistent || !Objects.equals(factory, first.getFactoryCode())) {
            throw new CheckedException("MES来源数量请求分录不一致或跨工厂");
        }
        result.setRequestFingerprint(first.getRequestFingerprint()).setEventKind(first.getEventKind())
                .setMesTaskId(first.getMesTaskId()).setMesTaskCode(first.getMesTaskCode())
                .setApsBatchNo(first.getApsBatchNo());

        MesTaskEntity task = getById(first.getMesTaskId());
        if (task == null || !Objects.equals(task.getCode(), first.getMesTaskCode())
                || !Objects.equals(factory, task.getFactoryCode())) {
            throw new CheckedException("MES来源数量分录关联的生产工单不存在或跨工厂");
        }
        Map<Long, MesApsBatchAllocationEntity> sourceAllocations = allocationRows(task).stream()
                .collect(Collectors.toMap(MesApsBatchAllocationEntity::getIssueId, Function.identity(),
                        (left, right) -> {
                            throw new CheckedException("MES来源分配账存在重复PLAN计划");
                        }, LinkedHashMap::new));
        boolean legacy = events.stream().anyMatch(item -> item.getSourceIssueId() == null);
        Set<Long> issueIds = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        List<MesSourceQuantityMappingDTO.Allocation> allocations = new ArrayList<>();
        for (MesApsSourceQuantityEventEntity event : events) {
            if (event.getQuantityDelta() == null || event.getQuantityDelta().signum() == 0
                    || event.getQuantityAfter() == null || event.getQuantityAfter().signum() < 0
                    || StrUtil.isBlank(event.getSourceIssueNo())
                    || event.getSourceIssueId() != null && !issueIds.add(event.getSourceIssueId())) {
                throw new CheckedException("MES来源数量请求存在空数量、空计划或重复计划分录");
            }
            MesApsBatchAllocationEntity sourceAllocation = event.getSourceIssueId() == null
                    ? null : sourceAllocations.get(event.getSourceIssueId());
            if (!legacy && (sourceAllocation == null
                    || !Objects.equals(sourceAllocation.getIssueNo(), event.getSourceIssueNo()))) {
                throw new CheckedException("MES来源数量请求与APS批次来源分配账不一致");
            }
            allocations.add(new MesSourceQuantityMappingDTO.Allocation()
                    .setIssueId(event.getSourceIssueId()).setIssueNo(event.getSourceIssueNo())
                    .setSourceAllocatedQuantity(sourceAllocation == null ? null : sourceAllocation.getAllocatedQuantity())
                    .setEventQuantity(event.getQuantityDelta()).setQuantityAfter(event.getQuantityAfter()));
            total = total.add(event.getQuantityDelta());
        }
        result.setAllocations(allocations).setEventQuantity(total);
        if (legacy) {
            result.setMappingStatus("LEGACY_PLAN_NO").setMappingComplete(false)
                    .setMappingMessage("历史工单数量分录只有PLAN计划号，禁止直接驱动上游逐计划事件");
        } else {
            result.setMappingStatus("APS_SOURCE_EVENT").setMappingComplete(true)
                    .setMappingMessage("已按本次MES请求解析" + allocations.size() + "张PLAN计划的实际数量增量");
        }
        result.setMappingFingerprint(sourceQuantityMappingFingerprint(result));
        return result;
    }

    private List<MesApsBatchAllocationEntity> allocationRows(MesTaskEntity task) {
        return apsBatchAllocationMapper.selectList(new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                .eq(MesApsBatchAllocationEntity::getMesTaskId, task.getId())
                .orderByAsc(MesApsBatchAllocationEntity::getAllocationSeq)
                .orderByAsc(MesApsBatchAllocationEntity::getId));
    }

    private void validateAllocationRows(MesTaskEntity task, List<MesApsBatchAllocationEntity> rows) {
        Set<Long> issueIds = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (MesApsBatchAllocationEntity row : rows) {
            if (!Objects.equals(task.getId(), row.getMesTaskId()) || row.getIssueId() == null
                    || StrUtil.isBlank(row.getIssueNo()) || row.getAllocatedQuantity() == null
                    || row.getAllocatedQuantity().signum() <= 0 || !issueIds.add(row.getIssueId())
                    || !Objects.equals(task.getFactoryCode(), row.getFactoryCode())) {
                throw new CheckedException("MES工单APS来源分配账不完整、重复或跨工厂：" + task.getCode());
            }
            total = total.add(row.getAllocatedQuantity());
        }
        if (task.getNoticeNum() == null || total.compareTo(task.getNoticeNum()) != 0) {
            throw new CheckedException("MES工单数量与APS来源分配账不守恒：" + task.getCode());
        }
    }

    private MesPlanSourceMappingDTO.Allocation mappingAllocation(MesApsBatchAllocationEntity row) {
        return new MesPlanSourceMappingDTO.Allocation().setApsTaskId(row.getApsTaskId())
                .setApsBatchId(row.getApsBatchId()).setApsBatchNo(row.getApsBatchNo())
                .setAllocationSeq(row.getAllocationSeq()).setIssueId(row.getIssueId()).setIssueNo(row.getIssueNo())
                .setIssueQuantitySnapshot(row.getIssueQuantitySnapshot()).setAllocatedQuantity(row.getAllocatedQuantity())
                .setCompletedQuantity(row.getCompletedQuantity()).setUnqualifiedQuantity(row.getUnqualifiedQuantity())
                .setQualityIsolatedQuantity(row.getQualityIsolatedQuantity())
                .setQualityReleasedQuantity(row.getQualityReleasedQuantity())
                .setQualityRejectedQuantity(row.getQualityRejectedQuantity()).setScrapQuantity(row.getScrapQuantity())
                .setReworkQuantity(row.getReworkQuantity()).setReworkCompletedQuantity(row.getReworkCompletedQuantity())
                .setReworkQualifiedQuantity(row.getReworkQualifiedQuantity()).setReworkRejectedQuantity(row.getReworkRejectedQuantity());
    }

    private String planSourceMappingFingerprint(MesPlanSourceMappingDTO mapping) {
        try {
            String raw = JSONUtil.toJsonStr(mapping.getAllocations()) + '|' + mapping.getFactoryCode() + '|'
                    + mapping.getMesTaskId() + '|' + mapping.getMesTaskCode() + '|' + mapping.getProduceTaskId()
                    + '|' + mapping.getProcessCode() + '|' + mapping.getMappingStatus();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("MES来源计划映射指纹生成失败");
        }
    }

    private String sourceQuantityMappingFingerprint(MesSourceQuantityMappingDTO mapping) {
        try {
            String raw = JSONUtil.toJsonStr(mapping.getAllocations()) + '|' + mapping.getRequestId() + '|'
                    + mapping.getRequestFingerprint() + '|' + mapping.getEventKind() + '|' + mapping.getMesTaskId()
                    + '|' + mapping.getFactoryCode() + '|' + mapping.getMappingStatus();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("MES来源数量请求映射摘要生成失败");
        }
    }

    private String trimToNull(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

	/**
	 * 查询当前实名租户、实际读取工厂的生产工单及其同组织工序进度。
	 * 客户端组织字段仅作为筛选输入，不能扩大服务端实际范围；跨组织或编码歧义返回明确失败。
	 *
	 * @param pageParam 分页条件
	 * @param query 业务筛选；非空组织条件必须与当前实际租户、工厂一致
	 * @return 当前工厂工单分页，工序进度只关联本页原工单的同组织来源
	 */
	@Override
	public IPage<MesTaskEntity> queryPage(Page<MesTaskEntity> pageParam, MesTaskEntity query) {
		String factory = factoryAccess.require("read");
		Long tenant = TenantContextHolder.getTenantId();
		if (tenant == null || tenant <= 0 || pageParam == null || query == null) {
			throw new CheckedException("生产工单分页缺少实际租户或有效查询条件");
		}
		if (query.getTenantId() != null && !Objects.equals(query.getTenantId(), tenant)
				|| StrUtil.isNotBlank(query.getFactoryCode()) && !Objects.equals(query.getFactoryCode(), factory)) {
			throw new CheckedException("生产工单筛选组织与当前实际租户、工厂不一致");
		}
		QueryWrapper<MesTaskEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
		// 不能依赖客户端factory LIKE或通用拦截器；父单和子进度分别精确限定同一真实组织。
		autoQueryWrapper.lambda().eq(MesTaskEntity::getTenantId, tenant)
				.eq(MesTaskEntity::getFactoryCode, factory).orderByDesc(MesTaskEntity::getCreateTime);
		IPage<MesTaskEntity> page = this.page(pageParam, autoQueryWrapper);
		if (page == null || page.getRecords() == null) {
			throw new CheckedException("生产工单分页结果无法核验");
		}
		Set<String> taskCodes = new LinkedHashSet<>();
		for (MesTaskEntity task : page.getRecords()) {
			if (task == null || task.getId() == null || task.getId() <= 0
					|| !Objects.equals(task.getTenantId(), tenant) || !Objects.equals(task.getFactoryCode(), factory)
					|| StrUtil.isBlank(task.getCode()) || !taskCodes.add(task.getCode())) {
				throw new CheckedException("工单分页归属缺失、跨组织或编码不唯一，禁止混合工序进度");
			}
		}
		if (taskCodes.isEmpty()) {
			return page;
		}
		List<MesProduceTaskEntity> processes = produceTaskService.list(Wrappers.<MesProduceTaskEntity>lambdaQuery()
				.eq(MesProduceTaskEntity::getTenantId, tenant).eq(MesProduceTaskEntity::getFactoryCode, factory)
				.in(MesProduceTaskEntity::getTaskCode, taskCodes));
		if (processes == null || processes.stream().anyMatch(process -> process == null
				|| !Objects.equals(process.getTenantId(), tenant) || !Objects.equals(process.getFactoryCode(), factory)
				|| !taskCodes.contains(process.getTaskCode()))) {
			throw new CheckedException("工单工序进度来源不属于当前实际租户、工厂或本页工单");
		}
		Map<String, List<MesProduceTaskEntity>> processesByTask = processes.stream()
				.collect(Collectors.groupingBy(MesProduceTaskEntity::getTaskCode));
		for (MesTaskEntity item : page.getRecords()) {
			List<MesProduceTaskEntity> members = processesByTask.getOrDefault(item.getCode(), List.of());
			if (members.isEmpty()) {
				continue;
			}
			List<RouteProcessDTO> processDTOS = new ArrayList<>();
			for (MesProduceTaskEntity task : members) {
				RouteProcessDTO process = new RouteProcessDTO();
				process.setProcessName(task.getRouteName());
				process.setFinishNum(task.getCompleteNum() != null ? task.getCompleteNum() : BigDecimal.ZERO);
				if (task.getNoticeNum() != null && task.getNoticeNum().compareTo(BigDecimal.ZERO) > 0) {
					BigDecimal percent = task.getCompleteNum() != null
							? task.getCompleteNum().multiply(new BigDecimal("100"))
									.divide(task.getNoticeNum(), 2, RoundingMode.HALF_UP)
								: BigDecimal.ZERO;
					process.setPercent(percent);
				} else {
					process.setPercent(BigDecimal.ZERO);
				}
				processDTOS.add(process);
			}
			item.setProcessList(processDTOS);
		}
		return page;
	}

    /**
     * 根据生产计划生成生产工单
     *
     * @param receive
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesTaskIssueReceiptDTO add(MesTaskReceiveDTO receive) {
        if (receive == null) {
            throw new CheckedException("生产计划接收参数不能为空");
        }
        formalPlanReceiptVerifier.verify(receive);
        String issueRequestId = StrUtil.trim(receive.getSourceRequestId());
        String issueFingerprint = StrUtil.trim(receive.getSourceRequestFingerprint());
        if (StrUtil.isBlank(issueRequestId) || issueRequestId.length() > 100
                || StrUtil.isBlank(issueFingerprint) || !issueFingerprint.matches("[0-9a-f]{64}")) {
            throw new CheckedException("生产计划下达请求号或SHA-256载荷指纹不完整");
        }
        receive.setSourceRequestId(issueRequestId);
        receive.setSourceRequestFingerprint(issueFingerprint);
        baseMapper.acquireApsBatchTransactionLock("PLAN_ISSUE:" + issueRequestId);
        MesTaskEntity requestReplay = this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getSourceRequestId, issueRequestId), false);
        if (requestReplay != null) {
            requireSamePlanIssue(requestReplay, receive);
            return issueReceipt(requestReplay, true);
        }
        boolean apsBatch = StrUtil.isNotBlank(receive.getApsBatchNo());
        if (apsBatch) {
            String batchNo = receive.getApsBatchNo().trim();
            if (batchNo.length() > 100 || receive.getApsTaskId() == null || receive.getApsBatchId() == null
                    || StrUtil.isBlank(receive.getApsBatchFingerprint())
                    || !Set.of("SINGLE", "SPLIT", "MERGED").contains(
                    StrUtil.blankToDefault(receive.getApsBatchType(), "").toUpperCase(Locale.ROOT))) {
                throw new CheckedException("APS批次标识、类型或来源指纹不完整");
            }
            if (receive.getNoticeNum() == null || receive.getNoticeNum().signum() <= 0
                    || CollUtil.isEmpty(receive.getApsBatchAllocations())) {
                throw new CheckedException("APS批次数量和来源分配不能为空");
            }
            baseMapper.acquireApsBatchTransactionLock(batchNo);
            MesTaskEntity existing = this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getApsBatchNo, batchNo), false);
            if (existing != null) {
                if (!Objects.equals(existing.getApsBatchFingerprint(), receive.getApsBatchFingerprint())
                        || !Objects.equals(existing.getApsTaskId(), receive.getApsTaskId())
                        || !Objects.equals(existing.getApsBatchId(), receive.getApsBatchId())
                        || !Objects.equals(existing.getApsBatchType(), receive.getApsBatchType().toUpperCase(Locale.ROOT))
                        || !sameDecimal(existing.getNoticeNum(), receive.getNoticeNum())
                        || !sameBatchAllocations(existing.getId(), receive.getApsBatchAllocations())) {
                    throw new CheckedException("APS批次号已存在，但来源指纹或数量不一致");
                }
                return issueReceipt(existing, true);
            }
            Set<Long> issueIds = new HashSet<>();
            Set<Integer> allocationSeqs = new HashSet<>();
            BigDecimal allocated = BigDecimal.ZERO;
            int expectedSeq = 1;
            for (MesApsBatchAllocationDTO allocation : receive.getApsBatchAllocations()) {
                int sequence = allocation == null || allocation.getAllocationSeq() == null
                        ? expectedSeq : allocation.getAllocationSeq();
                if (allocation == null || allocation.getIssueId() == null || StrUtil.isBlank(allocation.getIssueNo())
                        || allocation.getIssueQuantitySnapshot() == null
                        || allocation.getIssueQuantitySnapshot().signum() <= 0
                        || allocation.getAllocatedQuantity() == null || allocation.getAllocatedQuantity().signum() <= 0
                        || allocation.getAllocatedQuantity().compareTo(allocation.getIssueQuantitySnapshot()) > 0
                        || sequence <= 0 || !allocationSeqs.add(sequence) || !issueIds.add(allocation.getIssueId())) {
                    throw new CheckedException("APS批次来源计划重复、缺失或数量无效");
                }
                allocation.setAllocationSeq(sequence);
                allocated = allocated.add(allocation.getAllocatedQuantity());
                expectedSeq++;
            }
            if (allocated.compareTo(receive.getNoticeNum()) != 0) {
                throw new CheckedException("APS批次来源分配量与MES工单数量不守恒");
            }
            receive.setApsBatchNo(batchNo);
            receive.setApsBatchType(receive.getApsBatchType().toUpperCase(Locale.ROOT));
        }
        // 普通PLAN/APS工单的路线与BOM只能从MDM精确工程版本包生成。
        MesEngineeringPackageService.FrozenEngineering engineering = engineeringPackages.resolve(receive);
//        if (Objects.isNull(receive.getPlanNo())) {
//            throw new CheckedException("计划单号不允许为空，请检查参数！");
//        }
        // todo mrp运算产生的计划号相同，产品不同
//        long count = this.count(new LambdaQueryWrapper<MesTask>().eq(MesTaskEntity::getPlanNo, receive.getPlanNo()));
//        if (count > 0) {
//            throw new CheckedException(String.format("计划已下发到生产工单，请勿重复下发！计划单号:%s", receive.getPlanNo()));
//        }
        MesTaskEntity saveTask = new MesTaskEntity();
        BeanUtil.copyProperties(receive, saveTask);
        saveTask.setEngineeringReleaseId(engineering.source().getReleaseId());
        saveTask.setEngineeringContentHash(engineering.source().getContentHash());
        saveTask.setEngineeringVersionNo(engineering.source().getVersionNo());
        saveTask.setEngineeringBundleSnapshot(engineering.source().getBundleSnapshot());
        saveTask.setProcessRouteId(engineering.source().getRouteId());
        saveTask.setProcessRouteCode(engineering.source().getRouteCode());
        saveTask.setProcessRouteSnapshot(engineering.routeSnapshot());
        saveTask.setBomId(engineering.source().getBomId());
        saveTask.setBomVersion(engineering.source().getBomVersion());
        saveTask.setBomSnapshot(engineering.bomSnapshot());
        // 物料规格型号回填
        if (StrUtil.isNotEmpty(saveTask.getMatCode()) && StrUtil.isEmpty(saveTask.getMatSpec())) {
            MdmMaterialEntity mat = materialService.getDetails(saveTask.getMatCode()).getData();
            if (Objects.nonNull(mat)) {
                saveTask.setMatSpec(mat.getSpecificationModel());
            }
        }
        saveTask.setCode(remoteSeqConfigService.create("mes_task").getData());
        saveTask.setStatus(MesTaskStatusEnum.CREATE.name());
        if (!this.save(saveTask)) {
            throw new CheckedException("生产工单保存失败");
        }
        productionOrderService.recordReceivedOrder(receive, saveTask);
        if (apsBatch) {
            int allocationSeq = 1;
            for (MesApsBatchAllocationDTO source : receive.getApsBatchAllocations()) {
                MesApsBatchAllocationEntity allocation = new MesApsBatchAllocationEntity()
                        .setApsTaskId(receive.getApsTaskId()).setApsBatchId(receive.getApsBatchId())
                        .setApsBatchNo(receive.getApsBatchNo())
                        .setApsBatchFingerprint(receive.getApsBatchFingerprint())
                        .setMesTaskId(saveTask.getId()).setMesTaskCode(saveTask.getCode())
                        .setAllocationSeq(source.getAllocationSeq() == null ? allocationSeq : source.getAllocationSeq())
                        .setIssueId(source.getIssueId()).setIssueNo(source.getIssueNo())
                        .setIssueQuantitySnapshot(source.getIssueQuantitySnapshot())
                        .setAllocatedQuantity(source.getAllocatedQuantity())
                        .setCompletedQuantity(BigDecimal.ZERO).setInStockQuantity(BigDecimal.ZERO)
                        .setUnqualifiedQuantity(BigDecimal.ZERO).setQualityRejectedQuantity(BigDecimal.ZERO)
                        .setScrapQuantity(BigDecimal.ZERO).setReworkQuantity(BigDecimal.ZERO)
                        .setReworkCompletedQuantity(BigDecimal.ZERO).setReworkQualifiedQuantity(BigDecimal.ZERO)
                        .setReworkRejectedQuantity(BigDecimal.ZERO).setFactoryCode(receive.getFactoryCode());
                if (apsBatchAllocationMapper.insert(allocation) != 1) {
                    throw new CheckedException("APS批次来源分配账保存失败");
                }
                allocationSeq++;
            }
        }
        return issueReceipt(saveTask, false);
    }

    /**
     * 同一永久请求号只允许重放完全相同的计划事实。
     */
    private void requireSamePlanIssue(MesTaskEntity existing, MesTaskReceiveDTO incoming) {
        if (!Objects.equals(existing.getSourceRequestFingerprint(), incoming.getSourceRequestFingerprint())
                || !Objects.equals(existing.getPlanNo(), incoming.getPlanNo())
                || !Objects.equals(existing.getMatCode(), incoming.getMatCode())
                || !Objects.equals(existing.getFactoryCode(), incoming.getFactoryCode())
                || !Objects.equals(existing.getFormalPlanContractNo(), incoming.getFormalPlanContractNo())
                || !Objects.equals(existing.getFormalPlanContractVersion(), incoming.getFormalPlanContractVersion())
                || !Objects.equals(existing.getFormalPlanContractFingerprint(),
                incoming.getFormalPlanContractFingerprint())
                || !Objects.equals(existing.getPlanningPolicyId(), incoming.getPlanningPolicyId())
                || !Objects.equals(existing.getPlanningPolicyVersion(), incoming.getPlanningPolicyVersion())
                || !Objects.equals(existing.getPlanningPolicyFingerprint(), incoming.getPlanningPolicyFingerprint())
                || !Objects.equals(existing.getMrpAuthority(), incoming.getMrpAuthority())
                || !Objects.equals(existing.getProductionOrder(), incoming.getProductionOrder())
                || !sameDecimal(existing.getNoticeNum(), incoming.getNoticeNum())) {
            throw new CheckedException("生产计划下达请求号已使用，但载荷指纹或核心计划事实不一致");
        }
    }

    private MesTaskIssueReceiptDTO issueReceipt(MesTaskEntity task, boolean replayed) {
        return new MesTaskIssueReceiptDTO()
                .setRequestId(task.getSourceRequestId())
                .setRequestFingerprint(task.getSourceRequestFingerprint())
                .setTaskId(task.getId())
                .setTaskCode(task.getCode())
                .setPlanNo(task.getPlanNo())
                .setAcceptedTime(task.getCreateTime() == null ? LocalDateTime.now() : task.getCreateTime())
                .setReplayed(replayed);
    }

    private boolean sameBatchAllocations(Long mesTaskId, List<MesApsBatchAllocationDTO> incoming) {
        List<MesApsBatchAllocationEntity> existing = apsBatchAllocationMapper.selectList(
                new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                        .eq(MesApsBatchAllocationEntity::getMesTaskId, mesTaskId));
        if (incoming == null || existing.size() != incoming.size()) return false;
        Map<Long, MesApsBatchAllocationEntity> byIssue = existing.stream().collect(Collectors.toMap(
                MesApsBatchAllocationEntity::getIssueId, Function.identity()));
        int ordinal = 1;
        Set<Long> seenIssues = new HashSet<>();
        for (MesApsBatchAllocationDTO source : incoming) {
            MesApsBatchAllocationEntity saved = source == null ? null : byIssue.get(source.getIssueId());
            Integer incomingSeq = source == null || source.getAllocationSeq() == null ? ordinal : source.getAllocationSeq();
            if (saved == null || !seenIssues.add(source.getIssueId()) || !Objects.equals(saved.getIssueNo(), source.getIssueNo())
                    || !Objects.equals(saved.getAllocationSeq(), incomingSeq)
                    || !sameDecimal(saved.getIssueQuantitySnapshot(), source.getIssueQuantitySnapshot())
                    || !sameDecimal(saved.getAllocatedQuantity(), source.getAllocatedQuantity())) return false;
            ordinal++;
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String addRework(MesTaskReceiveDTO receive) {
        if (receive == null || StrUtil.isBlank(receive.getReworkRequestId())) {
            throw new CheckedException("返工请求号不能为空");
        }
        String requestId = receive.getReworkRequestId().trim();
        if (requestId.length() > 64) {
            throw new CheckedException("返工请求号长度不能超过64位");
        }
        MesQualityGateSourceEvidence.scope();
        String inspectionType = optionalReworkCode(receive.getSourceInspectionType());
        inspectionType = inspectionType == null ? null : inspectionType.toUpperCase(Locale.ROOT);
        if (inspectionType == null
                || !Set.of("PROCEDURE", "PRODUCT").contains(inspectionType)
                || StrUtil.isBlank(receive.getSourceTaskCode())
                || StrUtil.isBlank(receive.getMatCode())
                || receive.getNoticeNum() == null
                || receive.getNoticeNum().signum() <= 0
                || receive.getSourceInspectionTaskId() == null
                || receive.getSourceInspectionTaskId() <= 0
                || receive.getSourceHandleRecordId() == null
                || receive.getSourceHandleRecordId() <= 0)
            throw new CheckedException("返工必须提供实际来源工单、检验处置、物料和正数数量");
        MesTaskEntity existing =
                this.getOne(
                        new LambdaQueryWrapper<MesTaskEntity>()
                                .eq(MesTaskEntity::getReworkRequestId, requestId)
                                .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                                .eq(
                                        MesTaskEntity::getFactoryCode,
                                        FactoryContextHolder.getFactoryCode()));
        if (existing != null) return replayRework(existing, receive, inspectionType);
        MesTaskEntity source =
                this.getOne(
                        new LambdaQueryWrapper<MesTaskEntity>()
                                .eq(MesTaskEntity::getCode, receive.getSourceTaskCode().trim())
                                .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                                .eq(
                                        MesTaskEntity::getFactoryCode,
                                        FactoryContextHolder.getFactoryCode())
                                .last("FOR UPDATE"));
        if (source == null) throw new CheckedException("当前实际组织不存在返工来源生产工单");
        // 与首次空查询用不同SQL重查，避免原事务一级缓存掩盖并发首笔提交。
        existing =
                this.getOne(
                        new LambdaQueryWrapper<MesTaskEntity>()
                                .eq(MesTaskEntity::getReworkRequestId, requestId)
                                .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                                .eq(
                                        MesTaskEntity::getFactoryCode,
                                        FactoryContextHolder.getFactoryCode())
                                .last("FOR UPDATE"));
        if (existing != null) return replayRework(existing, receive, inspectionType);
        if (source.getBomId() == null || source.getProcessRouteId() == null
                || StrUtil.isBlank(source.getBomSnapshot()) || StrUtil.isBlank(source.getProcessRouteSnapshot())) {
            throw new CheckedException("原生产工单缺少冻结BOM或工艺路线，不能生成可执行返工工单");
        }
        if (!Objects.equals(source.getMatCode(), receive.getMatCode())) {
            throw new CheckedException("质量处置物料与原生产工单不一致");
        }

        List<MdmProcessRouteWorkingDTO> sourceRoute;
        try {
            sourceRoute = JSONUtil.toList(source.getProcessRouteSnapshot(), MdmProcessRouteWorkingDTO.class);
            normalizeRouteGraph(sourceRoute);
        } catch (Exception exception) {
            throw new CheckedException("原生产工单工艺路线快照损坏，不能生成返工工单");
        }
        ReworkRouteSelection selection = selectReworkRoute(source, sourceRoute, receive);
        MdmProcessRouteWorkingDTO reworkRoute = selection.route();
        String processCode = StrUtil.blankToDefault(reworkRoute.getProcessCode(), reworkRoute.getRouteCode());
        reworkRoute.setPreRouteCode(new String[0]);
        reworkRoute.setNextRouteCode(new String[0]);
        // 新返工图是独立的单节点图，两套关系必须同时裁剪；已批准的历史图不在下达时修写。
        reworkRoute.setPreRouteNodeCodes(new String[0]);
        reworkRoute.setNextRouteNodeCodes(new String[0]);
        reworkRoute.setOrderNum(1);
        reworkRoute.setRouteOrder(1);
        reworkRoute.setLastRoute(Boolean.TRUE);
        reworkRoute.setOverlapEnabled(Boolean.FALSE);
        reworkRoute.setTransferBatchQuantity(null);
        // 正式返工是QMS批准的独立工单，原正常路线的前序时间规则不能在缺少跨工单来源证据时移植。
        reworkRoute.setTimeCheck(Boolean.FALSE);
        reworkRoute.setTimeVal(null);
        reworkRoute.setTimingRulesJson(null);
        reworkRoute.setNodeTimingRulesJson(null);
        // 返工完成后由返工协调单生成独立复检，避免普通首/中/末检规则提前把返工工序锁死。
        reworkRoute.setIsInspection("0");

        MesTaskEntity rework = new MesTaskEntity();
        BeanUtil.copyProperties(
                source,
                rework,
                "id",
                "code",
                "status",
                "planNo",
                "materialReservationSnapshot",
                "apsOperationSnapshot",
                "createBy",
                "createTime",
                "updateBy",
                "updateTime");
        rework.setCode(remoteSeqConfigService.create("mes_task").getData());
        if (StrUtil.isBlank(rework.getCode())) {
            throw new CheckedException("返工工单编码生成失败");
        }
        rework.setResourceType("REWORK");
        rework.setReworkMaterialPlanId(null);
        // 二次返工可继承原生产工程版本和本次选定的原实际节点，
        // 但不得继承上一张返工单的双岗决策；每张新单都必须重新批准原标识及SOP/参数口径。
        rework.setReworkIdentifierScopeSnapshot(null);
        rework.setReworkIdentifierScopeFingerprint(null);
        rework.setReworkEngineeringMode(null);
        rework.setReworkEngineeringOverrideId(null);
        rework.setReworkEngineeringOverrideHash(null);
        rework.setReworkEngineeringScopeSnapshot(null);
        rework.setReworkEngineeringScopeFingerprint(null);
        rework.setBomSnapshot(reworkMaterialPlans.originalBom(source));
        rework.setResourceId(String.valueOf(receive.getSourceInspectionTaskId()));
        rework.setReworkRequestId(requestId);
        rework.setSourceTaskCode(source.getCode());
        rework.setSourceProduceTaskCode(selection.sourceProduceTaskCode());
        rework.setSourceInspectionType(inspectionType);
        rework.setSourceInspectionTaskId(receive.getSourceInspectionTaskId());
        rework.setSourceHandleRecordId(receive.getSourceHandleRecordId());
        rework.setReworkProcessCode(processCode);
        rework.setReworkRouteNodeCode(selection.routeNodeCode());
        // 每张正式返工都必须由新的双岗执行方案明确批准时间口径，不能继承上游返工单的决策。
        rework.setReworkTimingPolicySnapshot(null);
        rework.setReworkReason(StrUtil.trim(receive.getReworkReason()));
        rework.setReworkCallbackStatus("PENDING");
        rework.setReworkCallbackCount(0);
        rework.setReinspectionTaskId(null);
        rework.setReinspectionStatus("WAIT");
        rework.setReinspectionMessage("返工尚未完工");
        rework.setReworkQualifiedNum(BigDecimal.ZERO);
        rework.setReworkRejectedNum(BigDecimal.ZERO);
        rework.setProcessRouteSnapshot(JSONUtil.toJsonStr(List.of(reworkRoute)));
        if (StrUtil.isNotBlank(selection.routeNodeCode())) {
            // 返工创建就核原节点正式SOP证据，不让用户完成方案复核后才在下达时发现来源不可用。
            sopFreezeService.validateReworkSnapshot(rework, List.of(reworkRoute));
            MesProcessParameterStandardFreezeService.FrozenBundle inheritedParameters =
                    parameterStandardFreezeService.inheritReworkNode(source, selection.sourceProduceTask(),
                            rework, reworkRoute);
            rework.setProcessParameterStandardNo(inheritedParameters.catalog().getStandardNo());
            rework.setProcessParameterStandardVersion(inheritedParameters.catalog().getVersionNo());
            rework.setProcessParameterStandardSnapshot(inheritedParameters.snapshot());
            rework.setProcessParameterSnapshotFingerprint(inheritedParameters.fingerprint());
        }
        rework.setMaterialReservationSnapshot(null);
        rework.setApsOperationSnapshot(null);
        rework.setApsTaskId(null);
        rework.setApsBatchId(null);
        rework.setApsBatchNo(null);
        rework.setApsBatchType(null);
        rework.setApsBatchFingerprint(null);
        rework.setPlanNo(null);
        rework.setNoticeNum(receive.getNoticeNum());
        rework.setDayNeedNum(receive.getNoticeNum());
        rework.setCompleteNum(BigDecimal.ZERO);
        rework.setInStockNum(BigDecimal.ZERO);
        rework.setUnInStockNum(BigDecimal.ZERO);
        rework.setUnPassNum(BigDecimal.ZERO);
        rework.setPlannedNum(BigDecimal.ZERO);
        rework.setAlreadyNoticeNum(BigDecimal.ZERO);
        rework.setStartTime(null);
        rework.setFinishTime(null);
        rework.setStatus(MesTaskStatusEnum.CREATE.name());
        rework.setPlanStartDate(LocalDateTime.now());
        rework.setPlanEndDate(LocalDateTime.now().plusDays(1));
        if (!this.save(rework)) {
            throw new CheckedException("返工工单保存失败，请保留原返工请求号重试");
        }
        if (source.getPlanNo() != null || source.getApsBatchNo() != null) {
            sourceQuantityService.record(source, "REWORK-CREATED:" + requestId,
                    MesSourceQuantityKind.REWORK_CREATED, receive.getNoticeNum(), requestId,
                    "质量处置创建返工工单【" + rework.getCode() + "】，返工数量【" + receive.getNoticeNum()
                            + "】，原工单【" + source.getCode() + "】");
        }
        return rework.getCode();
    }

    private String replayRework(
            MesTaskEntity row, MesTaskReceiveDTO command, String inspectionType) {
        if (!"REWORK".equals(row.getResourceType())
                || StrUtil.isBlank(row.getCode())
                || !Objects.equals(row.getSourceInspectionType(), inspectionType)
                || !Objects.equals(
                        row.getSourceInspectionTaskId(), command.getSourceInspectionTaskId())
                || !Objects.equals(row.getSourceHandleRecordId(), command.getSourceHandleRecordId())
                || !Objects.equals(row.getSourceTaskCode(), command.getSourceTaskCode().trim())
                || !Objects.equals(
                        optionalReworkCode(row.getSourceProduceTaskCode()),
                        optionalReworkCode(command.getSourceProduceTaskCode()))
                || !Objects.equals(row.getMatCode(), command.getMatCode())
                || row.getNoticeNum() == null
                || row.getNoticeNum().compareTo(command.getNoticeNum()) != 0
                || !Objects.equals(
                        optionalReworkCode(row.getReworkReason()),
                        optionalReworkCode(command.getReworkReason()))
                || StrUtil.isNotBlank(command.getReworkProcessCode())
                        && !Objects.equals(
                                row.getReworkProcessCode(), command.getReworkProcessCode().trim()))
            throw new CheckedException("返工原请求号对应不同来源、物料、数量、工序或原因，不能重复成功");
        return row.getCode();
    }

    private String optionalReworkCode(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    /**
     * 正式返工先锁定原实际工序任务，再解析稳定节点。主工序编码只用于核对和兼容旧唯一路线，
     * 不能在重复主工序路线里用findFirst猜选某一次加工。
     */
    private ReworkRouteSelection selectReworkRoute(MesTaskEntity source,
                                                   List<MdmProcessRouteWorkingDTO> sourceRoute,
                                                   MesTaskReceiveDTO receive) {
        boolean nodeGraph = sourceRoute.stream().anyMatch(route -> StrUtil.isNotBlank(route.getRouteNodeCode()));
        String requestedProcess = StrUtil.trim(receive.getReworkProcessCode());
        String sourceProduceTaskCode = StrUtil.trim(receive.getSourceProduceTaskCode());
        MesProduceTaskEntity sourceProduceTask = null;
        if (StrUtil.isNotBlank(sourceProduceTaskCode)) {
            List<MesProduceTaskEntity> matches = produceTaskService.list(
                    new LambdaQueryWrapper<MesProduceTaskEntity>()
                            .eq(MesProduceTaskEntity::getTenantId, source.getTenantId())
                            .eq(MesProduceTaskEntity::getFactoryCode, source.getFactoryCode())
                            .eq(MesProduceTaskEntity::getTaskCode, source.getCode())
                            .eq(MesProduceTaskEntity::getCode, sourceProduceTaskCode));
            if (matches.size() != 1) {
                throw new CheckedException("返工来源实际工序任务未唯一归属原生产工单：" + sourceProduceTaskCode);
            }
            sourceProduceTask = matches.get(0);
            if (StrUtil.isNotBlank(requestedProcess)
                    && !requestedProcess.equals(sourceProduceTask.getRouteCode())) {
                throw new CheckedException("质量处置返工工序与原实际工序任务不一致："
                        + requestedProcess + "/" + sourceProduceTask.getRouteCode());
            }
            requestedProcess = sourceProduceTask.getRouteCode();
        }

        String requestedNode = sourceProduceTask == null ? null
                : StrUtil.trim(sourceProduceTask.getSourceRouteNodeCode());
        if (nodeGraph && StrUtil.isBlank(requestedNode)) {
            throw new CheckedException("原生产工单使用稳定节点执行，正式返工必须携带原实际工序任务及节点身份");
        }
        List<MdmProcessRouteWorkingDTO> candidates;
        if (StrUtil.isNotBlank(requestedNode)) {
            String exactNode = requestedNode;
            candidates = sourceRoute.stream().filter(route -> exactNode.equals(route.getRouteNodeCode())).toList();
        } else if (StrUtil.isNotBlank(requestedProcess)) {
            String exactProcess = requestedProcess;
            candidates = sourceRoute.stream().filter(route -> exactProcess.equals(
                    StrUtil.blankToDefault(route.getProcessCode(), route.getRouteCode()))).toList();
        } else {
            candidates = sourceRoute.stream().filter(route ->
                    nodeGraph ? route.getNextRouteNodeCodes() != null && route.getNextRouteNodeCodes().length == 0
                            : route.getNextRouteCode() == null || route.getNextRouteCode().length == 0).toList();
        }
        if (candidates.size() != 1) {
            throw new CheckedException(StrUtil.isNotBlank(requestedNode)
                    ? "原实际工序任务未唯一匹配冻结路线节点：" + requestedNode
                    : "返工工序在原冻结路线中缺失或不唯一，必须从具体实际工序任务发起：" + requestedProcess);
        }
        MdmProcessRouteWorkingDTO route = candidates.get(0);
        String process = StrUtil.blankToDefault(route.getProcessCode(), route.getRouteCode());
        if (sourceProduceTask != null && (!Objects.equals(process, sourceProduceTask.getRouteCode())
                || (StrUtil.isNotBlank(requestedNode) && !Objects.equals(route.getRouteNodeCode(), requestedNode)))) {
            throw new CheckedException("返工来源实际工序与原冻结路线节点/主工序不一致");
        }
        return new ReworkRouteSelection(route, sourceProduceTask, sourceProduceTaskCode,
                StrUtil.trim(route.getRouteNodeCode()));
    }

    @Override
    public Long notifyReworkCompletion(Long taskId, String produceTaskCode, BigDecimal completedQuantity) {
        return reworkCompletions.notifyCompletion(taskId, produceTaskCode, completedQuantity);
    }

    @Override
    public Long retryReworkCallback(Long taskId) {
        MesQualityGateSourceEvidence.scope();
        factoryAccess.require("write");
        return reworkCompletions.retry(taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncReworkInspection(MesReworkInspectionResultDTO result) {
        if (result == null || StrUtil.isBlank(result.getRequestId()) || result.getQmsTaskId() == null) {
            throw new CheckedException("返工复检同步缺少返工请求号或QMS任务ID");
        }
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || tenant <= 0 || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || StrUtil.isBlank(factory)) {
            throw new CheckedException("返工复检同步缺少实际租户或工厂");
        }
        String requestId = result.getRequestId().trim();
        var owners = this.list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getReworkRequestId, requestId).last("LIMIT 2"));
        if (owners.size() != 1 || !"REWORK".equals(owners.get(0).getResourceType())) {
            throw new CheckedException("未找到匹配的MES返工工单");
        }
        MesTaskEntity observed = owners.get(0);
        // 与普通报工、完工和质量同步同序：业务锁必须先于根行锁。
        bindingGroups.lockDispatchTask(observed.getCode());
        var locked = this.list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getReworkRequestId, requestId).last("LIMIT 2 FOR UPDATE"));
        if (locked.size() != 1 || !Objects.equals(observed.getId(), locked.get(0).getId())
                || !Objects.equals(observed.getCode(), locked.get(0).getCode())
                || !"REWORK".equals(locked.get(0).getResourceType())) {
            throw new CheckedException("返工复检工单归属已变化，请重新同步");
        }
        MesTaskEntity task = locked.get(0);
        if (!Objects.equals(task.getReinspectionTaskId(), result.getQmsTaskId())) {
            throw new CheckedException("复检任务与MES返工工单登记不一致");
        }
        MesProduceTaskEntity finalTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, tenant)
                .eq(MesProduceTaskEntity::getFactoryCode, factory)
                .eq(MesProduceTaskEntity::getTaskCode, task.getCode())
                .eq(MesProduceTaskEntity::getLastRoute, Boolean.TRUE));
        if (finalTask == null) {
            throw new CheckedException("返工工单缺少最终工序，不能同步复检数量");
        }
        BigDecimal total = BigDecimal.valueOf(Objects.requireNonNullElse(result.getTotalNum(), 0));
        BigDecimal usable = BigDecimal.valueOf(Objects.requireNonNullElse(result.getUsableNum(), 0));
        BigDecimal handled = BigDecimal.valueOf(Objects.requireNonNullElse(result.getHandleNum(), 0));
        BigDecimal completed = Objects.requireNonNullElse(finalTask.getCompleteNum(), BigDecimal.ZERO);
        if (total.signum() <= 0 || total.compareTo(completed) > 0 || usable.signum() < 0 || handled.signum() < 0
                || usable.compareTo(handled) > 0 || handled.compareTo(total) > 0) {
            throw new CheckedException("返工复检总数、已处置数或MES完工数关系不正确");
        }
        boolean qualified = "QUALIFIED".equals(result.getResult());
        if (qualified) {
            boolean defaultDisposition = usable.signum() == 0 && handled.signum() == 0;
            if (!defaultDisposition && (usable.compareTo(total) != 0 || handled.compareTo(total) != 0)) {
                throw new CheckedException("返工复检合格结论与显式处置数量不一致");
            }
            usable = total;
            handled = total;
        }
        MesQualityGateDecision quantity = qualityQuantityService.reconcileRework(
                finalTask.getCode(), result.getQmsTaskId(), usable, handled, qualified);
        BigDecimal newQualified = Objects.requireNonNullElse(quantity.getReleasedQuantity(), BigDecimal.ZERO);
        BigDecimal newRejected = Objects.requireNonNullElse(quantity.getRejectedQuantity(), BigDecimal.ZERO);
        BigDecimal oldQualified = Objects.requireNonNullElse(task.getReworkQualifiedNum(), BigDecimal.ZERO);
        BigDecimal oldRejected = Objects.requireNonNullElse(task.getReworkRejectedNum(), BigDecimal.ZERO);
        if (newQualified.compareTo(oldQualified) < 0 || newRejected.compareTo(oldRejected) < 0) {
            throw new CheckedException("返工复检累计数量发生倒退，拒绝同步");
        }
        MesTaskEntity sourceTask = this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, task.getSourceTaskCode()));
        if (sourceTask == null) throw new CheckedException("返工复检缺少可归集的原生产工单");
        BigDecimal qualifiedDelta = newQualified.subtract(oldQualified);
        BigDecimal rejectedDelta = newRejected.subtract(oldRejected);
        boolean hasSourcePlan = sourceTask.getPlanNo() != null || sourceTask.getApsBatchNo() != null;
        if (hasSourcePlan && qualifiedDelta.signum() > 0) {
            sourceQuantityService.record(sourceTask,
                    "REWORK-QUALIFIED:" + task.getReworkRequestId() + ":" + newQualified.stripTrailingZeros().toPlainString(),
                    MesSourceQuantityKind.REWORK_QUALIFIED, qualifiedDelta, String.valueOf(result.getQmsTaskId()),
                    "返工工单【" + task.getCode() + "】复检累计放行【" + newQualified + "】");
        }
        if (hasSourcePlan && rejectedDelta.signum() > 0) {
            sourceQuantityService.record(sourceTask,
                    "REWORK-REJECTED:" + task.getReworkRequestId() + ":" + newRejected.stripTrailingZeros().toPlainString(),
                    MesSourceQuantityKind.REWORK_REJECTED, rejectedDelta, String.valueOf(result.getQmsTaskId()),
                    "返工工单【" + task.getCode() + "】复检累计拒绝【" + newRejected + "】");
        }
        String status;
        if (Objects.requireNonNullElse(quantity.getPendingQuantity(), BigDecimal.ZERO).signum() > 0) {
            status = Objects.requireNonNullElse(quantity.getReleasedQuantity(), BigDecimal.ZERO).signum() > 0
                    || Objects.requireNonNullElse(quantity.getRejectedQuantity(), BigDecimal.ZERO).signum() > 0
                    ? "PARTIAL" : "WAIT";
        } else if (Objects.requireNonNullElse(quantity.getRejectedQuantity(), BigDecimal.ZERO).signum() > 0
                && Objects.requireNonNullElse(quantity.getReleasedQuantity(), BigDecimal.ZERO).signum() == 0) {
            status = "REJECTED";
        } else {
            status = "RELEASED";
        }
        if (!this.update(new LambdaUpdateWrapper<MesTaskEntity>().eq(MesTaskEntity::getId, task.getId())
                .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, task.getCode())
                .set(MesTaskEntity::getReworkQualifiedNum, newQualified)
                .set(MesTaskEntity::getReworkRejectedNum, newRejected)
                .set(MesTaskEntity::getReinspectionStatus, status)
                .set(MesTaskEntity::getReinspectionMessage, "复检任务" + result.getQmsTaskId()
                        + "：放行" + quantity.getReleasedQuantity() + "，待判" + quantity.getPendingQuantity()
                        + "，拒绝" + quantity.getRejectedQuantity()))) {
            throw new CheckedException("返工复检结果保存失败，已回滚质量数量和库存变更");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordQualityDisposition(MesQualityDispositionDTO command) {
        if (command == null || StrUtil.isBlank(command.getDispositionKind())) {
            throw new CheckedException("QMS质量事实类型不能为空");
        }
        String kind = command.getDispositionKind().trim().toUpperCase(Locale.ROOT);
        if ("SCRAP".equals(kind)) {
            scrapMaterialService.createFromQualityDisposition(command);
            return;
        }
        String inspectionType = StrUtil.blankToDefault(command.getInspectionType(), "").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PROCEDURE", "PRODUCT").contains(inspectionType)
                || !Set.of("ISOLATE", "RELEASE", "CONCESSION").contains(kind)
                || StrUtil.isBlank(command.getRequestId()) || StrUtil.isBlank(command.getSourceTaskCode())
                || ("PROCEDURE".equals(inspectionType) && StrUtil.isBlank(command.getSourceProduceTaskCode()))
                || StrUtil.isBlank(command.getFactoryCode())
                || command.getQuantity() == null || command.getQuantity().signum() <= 0
                || StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("QMS隔离/放行事实必须包含类型、请求号、工单、工序任务、工厂、正数数量和原因");
        }
        String requestId = command.getRequestId().trim();
        if (requestId.length() > 120) throw new CheckedException("QMS质量事实请求号不能超过120位");
        MesTaskEntity task = this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, command.getSourceTaskCode().trim())
                .eq(MesTaskEntity::getFactoryCode, command.getFactoryCode().trim()), false);
        MesProduceTaskEntity produceTask = StrUtil.isBlank(command.getSourceProduceTaskCode()) ? null
                : produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getCode, command.getSourceProduceTaskCode().trim())
                .eq(MesProduceTaskEntity::getFactoryCode, command.getFactoryCode().trim()), false);
        if (task == null || (StrUtil.isNotBlank(command.getSourceProduceTaskCode())
                && (produceTask == null || !Objects.equals(produceTask.getTaskCode(), task.getCode())))) {
            throw new CheckedException("QMS质量事实关联的MES工单或工序任务不存在/归属不一致");
        }
        if (!Objects.equals(task.getMatCode(), command.getMatCode())) {
            throw new CheckedException("QMS质量事实物料与MES工单产品不一致");
        }
        MesSourceQuantityKind quantityKind = "ISOLATE".equals(kind)
                ? MesSourceQuantityKind.QUALITY_ISOLATED : MesSourceQuantityKind.QUALITY_RELEASED;
        sourceQuantityService.record(task, requestId, quantityKind, command.getQuantity(),
                command.getInspectionTaskId() + ":" + Objects.toString(command.getHandleRecordId(), "TASK"),
                "QMS【" + command.getInspectionType() + "】" + kind + "事实，数量【"
                        + command.getQuantity() + "】，原因【" + command.getReason() + "】");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
    }

	/**
	 * 只在真实父工单根锁及CREATE行锁内编辑允许配置；来源及执行事实不接受整实体回写。
	 * 手工单继续其原冻结来源校验，其他草稿按原工程/APS/返工约束决定可维护字段。
	 *
	 * @param incoming 含精确ID的草稿修改；空值沿原部分更新合同，不表示清除冻结字段
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public void edit(MesTaskEntity incoming) {
		if (incoming == null) {
			throw new CheckedException("生产工单不存在，无法编辑");
		}
		MesTaskEntity persisted = taskCommands.lock(incoming.getId());
		if (!MesTaskStatusEnum.CREATE.name().equals(persisted.getStatus())) {
			throw new CheckedException("生产工单已下达或开始，无法编辑");
		}
		taskCommands.requireNoChildren(persisted);
		if (MesManualTaskService.owns(persisted)) {
			manualTasks.editDraft(incoming);
			return;
		}
		if (persisted.getEngineeringReleaseId() != null) {
			assertEngineeringSourceUnchanged(persisted, incoming);
		}
		boolean legacySourceEditable = persisted.getEngineeringReleaseId() == null
				&& StrUtil.isBlank(persisted.getApsOperationSnapshot())
				&& persisted.getApsTaskId() == null && persisted.getApsBatchId() == null
				&& StrUtil.isBlank(persisted.getApsBatchNo()) && StrUtil.isBlank(persisted.getApsBatchFingerprint())
				&& persisted.getFormalPlanContractId() == null && StrUtil.isBlank(persisted.getFormalPlanContractNo())
				&& StrUtil.isBlank(persisted.getFormalPlanAllocationSnapshot())
				&& StrUtil.isBlank(persisted.getSourceRequestId())
				&& !"REWORK".equals(persisted.getResourceType()) && !"MANUAL".equals(persisted.getResourceType());
		assertDraftFieldsUnchanged(persisted, incoming, legacySourceEditable);
		LambdaUpdateWrapper<MesTaskEntity> update = draftCondition(persisted)
				.set(incoming.getBatchRuleCode() != null, MesTaskEntity::getBatchRuleCode, incoming.getBatchRuleCode())
				.set(incoming.getWrapCode() != null, MesTaskEntity::getWrapCode, incoming.getWrapCode())
				.set(incoming.getWrapCapacity() != null, MesTaskEntity::getWrapCapacity, incoming.getWrapCapacity())
				.set(incoming.getTransferCode() != null, MesTaskEntity::getTransferCode, incoming.getTransferCode())
				.set(MesTaskEntity::getUpdateBy, SecurityUtils.getAuthentication().getName())
				.set(MesTaskEntity::getUpdateTime, LocalDateTime.now());
		if (legacySourceEditable) {
			update.set(incoming.getType() != null, MesTaskEntity::getType, incoming.getType())
					.set(incoming.getMatCode() != null, MesTaskEntity::getMatCode, incoming.getMatCode())
					.set(incoming.getMatName() != null, MesTaskEntity::getMatName, incoming.getMatName())
					.set(incoming.getMatSpec() != null, MesTaskEntity::getMatSpec, incoming.getMatSpec())
					.set(incoming.getNoticeNum() != null, MesTaskEntity::getNoticeNum, incoming.getNoticeNum())
					.set(incoming.getPlanStartDate() != null, MesTaskEntity::getPlanStartDate,
							incoming.getPlanStartDate())
					.set(incoming.getPlanEndDate() != null, MesTaskEntity::getPlanEndDate, incoming.getPlanEndDate())
					.set(incoming.getWorkshopCode() != null, MesTaskEntity::getWorkshopCode, incoming.getWorkshopCode())
					.set(incoming.getWorkshopName() != null, MesTaskEntity::getWorkshopName, incoming.getWorkshopName())
					.set(incoming.getProcessRouteId() != null, MesTaskEntity::getProcessRouteId,
							incoming.getProcessRouteId())
					.set(incoming.getProcessRouteCode() != null, MesTaskEntity::getProcessRouteCode,
							incoming.getProcessRouteCode())
					.set(incoming.getProcessRouteSnapshot() != null, MesTaskEntity::getProcessRouteSnapshot,
							incoming.getProcessRouteSnapshot())
					.set(incoming.getBomId() != null, MesTaskEntity::getBomId, incoming.getBomId())
					.set(incoming.getBomVersion() != null, MesTaskEntity::getBomVersion, incoming.getBomVersion())
					.set(incoming.getBomSnapshot() != null, MesTaskEntity::getBomSnapshot, incoming.getBomSnapshot());
		}
		if (baseMapper.update(null, update) != 1) {
			throw new CheckedException("生产工单草稿保存未命中原状态，已回滚");
		}
	}

	/**
	 * 只匹配本事务锁定的准确父单及CREATE状态，不能按客户端code覆盖其他来源。
	 */
	private LambdaUpdateWrapper<MesTaskEntity> draftCondition(MesTaskEntity current) {
		return new LambdaUpdateWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getId, current.getId())
				.eq(MesTaskEntity::getTenantId, current.getTenantId())
				.eq(MesTaskEntity::getFactoryCode, current.getFactoryCode())
				.eq(MesTaskEntity::getCode, current.getCode())
				.eq(MesTaskEntity::getStatus, MesTaskStatusEnum.CREATE.name());
	}

	/**
	 * 实体只是既有请求载体，持久化字段默认只读；只有明确白名单允许改变。
	 * 审计字段由服务器维护且不参与客户端时间精度比较，空字段仍视为旧客户端未发送。
	 */
	private void assertDraftFieldsUnchanged(MesTaskEntity current, MesTaskEntity incoming, boolean legacy) {
		Set<String> editable = new HashSet<>(List.of("batchRuleCode", "wrapCode", "wrapCapacity", "transferCode"));
		if (legacy) {
			editable.addAll(List.of("type", "matCode", "matName", "matSpec", "noticeNum", "planStartDate",
					"planEndDate", "workshopCode", "workshopName", "processRouteId", "processRouteCode",
					"processRouteSnapshot", "bomId", "bomVersion", "bomSnapshot"));
		}
		Set<String> serverAudit = Set.of("createBy", "createTime", "updateBy", "updateTime");
		var before = new org.springframework.beans.BeanWrapperImpl(current);
		var proposed = new org.springframework.beans.BeanWrapperImpl(incoming);
		var fields = com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfo(MesTaskEntity.class)
				.getFieldList();
		for (var field : fields) {
			String name = field.getProperty();
			if (editable.contains(name) || serverAudit.contains(name)) {
				continue;
			}
			Object value = proposed.getPropertyValue(name);
			Object original = before.getPropertyValue(name);
			if (value == null || original == null && value instanceof String text && text.isEmpty()) {
				continue;
			}
			boolean same = value instanceof BigDecimal left && original instanceof BigDecimal right
					? left.compareTo(right) == 0 : Objects.equals(value, original);
			if (!same) {
				throw new CheckedException("工单身份、来源或执行依据不可通过草稿编辑修改：" + name);
			}
		}
	}

	/**
	 * 工程版本工单拒绝客户端携带的不同源事实，避免“服务端静默覆盖后看似保存成功”。
	 * 对旧客户端未发送的新字段保持兼容；冻结值不进入草稿编辑的可写白名单。
	 */
	private void assertEngineeringSourceUnchanged(MesTaskEntity persisted, MesTaskEntity incoming) {
		boolean engineeringChanged = incoming.getEngineeringReleaseId() != null
				&& !Objects.equals(persisted.getEngineeringReleaseId(), incoming.getEngineeringReleaseId());
		engineeringChanged |= StrUtil.isNotBlank(incoming.getEngineeringContentHash())
				&& !Objects.equals(persisted.getEngineeringContentHash(), incoming.getEngineeringContentHash());
		engineeringChanged |= incoming.getEngineeringVersionNo() != null
				&& !Objects.equals(persisted.getEngineeringVersionNo(), incoming.getEngineeringVersionNo());
		boolean productChanged = StrUtil.isNotBlank(incoming.getMatCode())
				&& !Objects.equals(persisted.getMatCode(), incoming.getMatCode());
		boolean quantityChanged = incoming.getNoticeNum() != null
				&& !sameDecimal(persisted.getNoticeNum(), incoming.getNoticeNum());
		boolean routeChanged = incoming.getProcessRouteId() != null
				&& !Objects.equals(persisted.getProcessRouteId(), incoming.getProcessRouteId());
		routeChanged |= StrUtil.isNotBlank(incoming.getProcessRouteCode())
				&& !Objects.equals(persisted.getProcessRouteCode(), incoming.getProcessRouteCode());
		boolean bomChanged = incoming.getBomId() != null
				&& !Objects.equals(persisted.getBomId(), incoming.getBomId());
		bomChanged |= StrUtil.isNotBlank(incoming.getBomVersion())
				&& !Objects.equals(persisted.getBomVersion(), incoming.getBomVersion());
		if (engineeringChanged || productChanged || quantityChanged || routeChanged || bomChanged) {
			throw new CheckedException(
					"已绑定工程版本的工单禁止在MES修改工程版本、产品、数量、路线或BOM；请从原来源发起受控变更");
		}
	}

	/**
	 * 在当前实名租户和实际读取工厂内查询准确原单，供普通命令后核对当前事实。
	 * 返回当前状态观察，不是某次编辑、下达或作废的永久命令回执。
	 *
	 * @param id 精确父工单ID
	 * @return 含真实组织、来源及当前状态的详情；不存在或未授权时拒绝
	 */
	@Override
	public MesTaskDTO findBydId(Long id) {
		MesTaskEntity mesTask = taskCommands.read(id);
		MesTaskDTO result = new MesTaskDTO();
		BeanUtil.copyProperties(mesTask, result);
		return result;
	}

	/**
	 * 取得真实根锁和父行后冻结工程执行依据并下达；CREATE状态精确写一行，失败整笔回滚。
	 *
	 * @param id 当前租户实际工厂下的准确父工单ID
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public void issue(Long id) {
		List<MesProduceTaskEntity> saveTaskList = new ArrayList<>();
		MesTaskEntity mesTask = taskCommands.lock(id);
		if (!MesTaskStatusEnum.CREATE.name().equals(mesTask.getStatus())) {
			throw new CheckedException("生产工单已下达或状态已变化，禁止重复下达");
		}
		// 批次规则只能在草稿维护；首次下达前核实际生成器，避免冻结成无法派工的工单。
		if (batchGeneratorService.getBatchGenerator(mesTask.getBatchRuleCode()) == null) {
			throw new CheckedException("请先在编辑工单中选择有效的批次类型并保存，再下达");
		}
		// 普通生产下达不重选今日默认路线；正式REWORK仍沿用原批准快照合同。
		MesEngineeringPackageService.FrozenEngineering engineering = "REWORK".equals(mesTask.getResourceType())
				? null : engineeringPackages.verifyBeforeIssue(mesTask);
		reworkMaterialPlans.assertReady(mesTask);
		taskCommands.requireNoChildren(mesTask);
		if (Objects.isNull(mesTask.getBomId())) {
			throw new CheckedException("请先维护bom信息");
		}
		if (Objects.isNull(mesTask.getProcessRouteId()) || StrUtil.isEmpty(mesTask.getProcessRouteSnapshot())) {
			throw new CheckedException("请先维护工艺路线信息");
		}
		// 根据工艺路线保存生产工序任务表
		List<MdmProcessRouteWorkingDTO> routeList = engineering == null
				? JSONUtil.toList(mesTask.getProcessRouteSnapshot(), MdmProcessRouteWorkingDTO.class)
				: engineeringPackages.executionRoutes(engineering);
		normalizeRouteGraph(routeList);
		if (engineering != null) {
			verifyInspectionConfiguration(mesTask, routeList);
		}
		// 新正式返工在QMS派发时已经用原实际工序锁定稳定节点，可冻结单节点执行合同；
		// 升级前没有节点身份的历史返工继续沿原合同，不能在下达时借当前MDM补造节点。
		boolean freezeExecutionGraph = !"REWORK".equals(mesTask.getResourceType())
				|| StrUtil.isNotBlank(mesTask.getReworkRouteNodeCode());
		if (freezeExecutionGraph) {
			mesTask.setProcessRouteSnapshot(
					MesRouteExecutionFreeze.prepare(mesTask.getProcessRouteSnapshot(), routeList));
		}
		boolean exactNodeRework = "REWORK".equals(mesTask.getResourceType())
				&& StrUtil.isNotBlank(mesTask.getReworkRouteNodeCode());
		MesProcessParameterStandardFreezeService.FrozenBundle parameterStandard = exactNodeRework
				? parameterStandardFreezeService.validateReworkSnapshot(mesTask, routeList)
				: engineering == null ? parameterStandardFreezeService.freeze(mesTask, routeList)
				: parameterStandardFreezeService.freezeFromEngineeringSnapshot(mesTask, routeList,
				engineering.parameterCatalogSnapshot());
		// 普通生产参数与SOP直接取工程批准包内的精确原版，不再按今日时间重新解析。
		if (exactNodeRework) {
			sopFreezeService.validateReworkSnapshot(mesTask, routeList);
		} else if (engineering == null) {
			sopFreezeService.freeze(mesTask, routeList);
		} else {
			sopFreezeService.freezeFromEngineeringSnapshot(mesTask, routeList,
					engineering.sopManifestSnapshot());
		}
		mesTask.setProcessRouteSnapshot(
				sopFreezeService.mergeFrozenSnapshot(mesTask.getProcessRouteSnapshot(), routeList));
		mesTask.setProcessParameterStandardNo(parameterStandard.catalog().getStandardNo());
		mesTask.setProcessParameterStandardVersion(parameterStandard.catalog().getVersionNo());
		mesTask.setProcessParameterStandardSnapshot(parameterStandard.snapshot());
		mesTask.setProcessParameterSnapshotFingerprint(parameterStandard.fingerprint());
		boolean hasApsSnapshot = StrUtil.isNotBlank(mesTask.getApsOperationSnapshot());
		List<MesApsOperationSnapshotDTO> apsOperations = new ArrayList<>();
		if (hasApsSnapshot) {
			try {
				apsOperations = JSONUtil.toList(mesTask.getApsOperationSnapshot(), MesApsOperationSnapshotDTO.class);
			} catch (Exception ex) {
				throw new CheckedException("APS工序资源快照损坏，禁止下达生产任务");
			}
			if (CollUtil.isEmpty(apsOperations) || apsOperations.stream().anyMatch(item -> item == null
					|| Boolean.TRUE.equals(item.getHardBlocked()) || !Boolean.TRUE.equals(item.getLockStatus()))) {
				throw new CheckedException("APS工序尚未全部完成有效锁定，禁止下达生产任务");
			}
		}
		Map<String, MesApsOperationSnapshotDTO> apsByNode = hasApsSnapshot
				? MesApsRouteNodeMatcher.matchAll(apsOperations, routeList, mesTask.getProcessRouteCode()) : Map.of();

		if (CollUtil.isNotEmpty(routeList)) {
			routeList.forEach(route -> {
				MesProduceTaskEntity saveTask = new MesProduceTaskEntity();
				saveTask.setCode(remoteSeqConfigService.create("mes_produce_task").getData());
				saveTask.setTaskCode(mesTask.getCode());
				saveTask.setMatCode(mesTask.getMatCode());
				saveTask.setMatName(mesTask.getMatName());
				saveTask.setMatSpec(mesTask.getMatSpec());
				saveTask.setRouteCode(StrUtil.blankToDefault(route.getProcessCode(), route.getRouteCode()));
				saveTask.setSourceRouteNodeCode(route.getRouteNodeCode());
				saveTask.setRouteName(StrUtil.blankToDefault(route.getProcessName(), route.getRouteName()));
				saveTask.setRouteType(route.getType());
				saveTask.setRouteOrder(route.getRouteOrder());
				saveTask.setPredecessorCodes(freezeExecutionGraph
						? String.join(",", route.getPreRouteCode()) : joinCodes(route.getPreRouteCode()));
				saveTask.setSuccessorCodes(freezeExecutionGraph
						? String.join(",", route.getNextRouteCode()) : joinCodes(route.getNextRouteCode()));
				saveTask.setOverlapEnabled(Boolean.TRUE.equals(route.getOverlapEnabled()));
				saveTask.setTransferBatchQuantity(Boolean.TRUE.equals(route.getOverlapEnabled())
						? route.getTransferBatchQuantity() : null);
				saveTask.setTransferMinutes(Boolean.TRUE.equals(route.getTimeCheck()) && route.getTimeVal() != null
						? route.getTimeVal().max(BigDecimal.ZERO).longValue() : 0L);
				saveTask.setRouteWorkCenter(JSONUtil.toJsonStr(route.getMdmProcessProcedureWorkList()));

				List<String> workCodes = route.getMdmProcessProcedureWorkList().stream()
						.map(MdmProcessRouteWorkCenterDTO::getWorkCode)
						.collect(Collectors.toList());
				List<String> workNames = route.getMdmProcessProcedureWorkList().stream()
						.map(MdmProcessRouteWorkCenterDTO::getWorkName)
						.collect(Collectors.toList());

				saveTask.setWorksiteCodes(String.join(",", workCodes));
				saveTask.setWorksiteNames(String.join(",", workNames));
				MesApsOperationSnapshotDTO aps = apsByNode.get(MesApsRouteNodeMatcher.routeKey(route));
				if (hasApsSnapshot && aps == null) {
					throw new CheckedException(
							String.format("工序【%s】未匹配到唯一APS锁定结果，禁止下达生产任务", route.getRouteName()));
				}
				if (aps != null) {
					if (!new LinkedHashSet<>(splitCodes(saveTask.getPredecessorCodes()))
							.equals(new LinkedHashSet<>(splitCodes(aps.getPredecessorCodes())))) {
						throw new CheckedException(
								String.format("工序【%s】的MES路线快照与APS前置关系不一致，禁止下达生产任务",
										route.getRouteName()));
					}
					if (Boolean.TRUE.equals(saveTask.getOverlapEnabled()) !=
							Boolean.TRUE.equals(aps.getOverlapEnabled())
							|| !sameDecimal(saveTask.getTransferBatchQuantity(), aps.getTransferBatchQuantity())) {
						throw new CheckedException(
								String.format("工序【%s】的MES路线快照与APS转移批量策略不一致，禁止下达生产任务",
										route.getRouteName()));
					}
					saveTask.setPlannedStartTime(aps.getScheduledStartTime());
					saveTask.setPlannedEndTime(aps.getScheduledEndTime());
					saveTask.setPlannedDeviceCode(aps.getDeviceCode());
					saveTask.setPlannedDeviceName(aps.getDeviceName());
					saveTask.setPlannedResourceSlotNo(aps.getResourceSlotNo());
					saveTask.setPlannedResourceGroupCode(aps.getResourceGroupCode());
					saveTask.setPlannedResourceGroupName(aps.getResourceGroupName());
					saveTask.setPlannedResourceGroupCapacity(aps.getResourceGroupCapacity());
					saveTask.setPlannedResourceGroupSlotNo(aps.getResourceGroupSlotNo());
					saveTask.setPlannedWorkerCode(aps.getEmployeeCode());
					saveTask.setPlannedWorkerName(aps.getEmployeeName());
					saveTask.setPlannedToolingCodes(aps.getToolingCodes());
					saveTask.setPlannedToolingNames(aps.getToolingNames());
					saveTask.setToolingAllocationSnapshot(aps.getToolingAllocationSnapshot());
					saveTask.setToolingLifeUsageSnapshot(aps.getToolingLifeUsageSnapshot());
					saveTask.setApsOperationSnapshot(JSONUtil.toJsonStr(aps));
					if ("STATION".equalsIgnoreCase(aps.getResourceType()) &&
							StrUtil.isNotBlank(aps.getResourceCode())) {
						saveTask.setPlannedWorksiteCode(aps.getResourceCode());
						saveTask.setPlannedWorksiteName(aps.getResourceName());
						saveTask.setWorksiteCodes(aps.getResourceCode());
						saveTask.setWorksiteNames(StrUtil.blankToDefault(aps.getResourceName(), aps.getResourceCode()));
					}
				}

				saveTask.setLastRoute(splitCodes(saveTask.getSuccessorCodes()).isEmpty());
				if (freezeExecutionGraph) {
					MesRouteExecutionFreeze.apply(route, saveTask);
				}
				saveTask.setProcessRouteId(mesTask.getProcessRouteId());
				saveTask.setProcessRouteSnapshot(mesTask.getProcessRouteSnapshot());
				saveTask.setTimingRulesJson(route.getTimingRulesJson());
				saveTask.setProcessRouteCode(mesTask.getProcessRouteCode());
				MesProcessParameterStandardFreezeService.ProcessSnapshot processParameter =
						parameterStandard.nodeSnapshots().get(route.getRouteNodeCode());
				if (processParameter == null) {
					throw new CheckedException(
							"路线节点【" + route.getRouteNodeCode() + "】缺少参数标准冻结结果，禁止下达生产任务");
				}
				saveTask.setProcessParameterStandardNo(parameterStandard.catalog().getStandardNo());
				saveTask.setProcessParameterStandardVersion(parameterStandard.catalog().getVersionNo());
				saveTask.setProcessParameterStandardSnapshot(processParameter.snapshot());
				saveTask.setProcessParameterSnapshotFingerprint(processParameter.fingerprint());
				saveTask.setNoticeNum(mesTask.getNoticeNum());
				saveTask.setNoticeDate(LocalDateTime.now());
				saveTask.setStatus(MesProduceTaskStatusEnum.WAIT.name());
				saveTask.setWorkshopCode(mesTask.getWorkshopCode());
				saveTask.setWorkshopName(mesTask.getWorkshopName());
				saveTask.setWrapCode(mesTask.getWrapCode());
				saveTask.setReportType(mesTask.getType());
				saveTask.setIsInspection(route.getIsInspection());
				// 首检登记在同一事务读取此对象。SQL 注入的 tenant_id 不会回填实体。
				saveTask.setTenantId(mesTask.getTenantId());
				saveTask.setFactoryCode(mesTask.getFactoryCode());
				saveTaskList.add(saveTask);
			});
		}
		// 先只保存原执行图，供子任务核真实父证据；参数目录仍随CREATE→ISSUE一次冻结。
		// 不提前提交状态或整实体，避免触发参数标准只能在首次下达时写入的数据库守卫。
		if (freezeExecutionGraph && baseMapper.update(null, draftCondition(mesTask)
				.set(MesTaskEntity::getProcessRouteSnapshot, mesTask.getProcessRouteSnapshot())) != 1) {
			throw new CheckedException("工单原执行图冻结保存失败");
		}
		if (!produceTaskService.saveBatch(saveTaskList)) {
			throw new CheckedException("工序任务冻结保存失败");
		}

		// 仅冻结本次下达生成的执行依据，不重写来源、数量或其他已存在父单事实。
		LambdaUpdateWrapper<MesTaskEntity> issueUpdate = draftCondition(mesTask)
				.set(MesTaskEntity::getProcessRouteSnapshot, mesTask.getProcessRouteSnapshot())
				.set(MesTaskEntity::getProcessParameterStandardNo, mesTask.getProcessParameterStandardNo())
				.set(MesTaskEntity::getProcessParameterStandardVersion, mesTask.getProcessParameterStandardVersion())
				.set(MesTaskEntity::getProcessParameterStandardSnapshot, mesTask.getProcessParameterStandardSnapshot())
				.set(MesTaskEntity::getProcessParameterSnapshotFingerprint,
						mesTask.getProcessParameterSnapshotFingerprint())
				.set(MesTaskEntity::getStatus, MesTaskStatusEnum.ISSUE.name())
				.set(MesTaskEntity::getUpdateBy, SecurityUtils.getAuthentication().getName())
				.set(MesTaskEntity::getUpdateTime, LocalDateTime.now());
		if (baseMapper.update(null, issueUpdate) != 1) {
			throw new CheckedException("生产工单下达状态保存未命中原状态，已回滚");
		}
		mesTask.setStatus(MesTaskStatusEnum.ISSUE.name());

		// 先保存质量排程，再向计划回执下达，避免本地排程失败时计划已推进。
		genInspectionTask(saveTaskList, mesTask.getMatCode());
		if (StrUtil.isNotEmpty(mesTask.getPlanNo())) {
			planMesStatusUpdate(mesTask);
		}
	}

    /**
     * 下达MES前再次校验路线快照DAG，并由前置关系重建后置关系和拓扑层级。
     */
    private void normalizeRouteGraph(List<MdmProcessRouteWorkingDTO> routeList) {
        if (CollUtil.isEmpty(routeList)) {
            return;
        }
        boolean nodeGraph = routeList.stream().anyMatch(route -> StrUtil.isNotBlank(route.getRouteNodeCode())
                || route.getPreRouteNodeCodes() != null || route.getNextRouteNodeCodes() != null);
        Map<String, MdmProcessRouteWorkingDTO> byCode = new LinkedHashMap<>();
        for (MdmProcessRouteWorkingDTO route : routeList) {
            String process = StrUtil.blankToDefault(route.getProcessCode(), route.getRouteCode());
            String code = nodeGraph ? route.getRouteNodeCode() : process;
            if (StrUtil.isBlank(process) || StrUtil.isBlank(code)
                    || nodeGraph && !code.matches("RN-[1-9][0-9]{0,18}")
                    || nodeGraph && (route.getPreRouteNodeCodes() == null || route.getNextRouteNodeCodes() == null)
                    || byCode.putIfAbsent(code, route) != null) {
                throw new CheckedException(nodeGraph
                        ? "生产工艺路线节点身份、主工序或节点关系不完整/重复：" + code
                        : "生产工艺路线快照存在空编码或重复工序：" + code);
            }
        }
        Map<String, LinkedHashSet<String>> predecessors = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> successors = new LinkedHashMap<>();
        byCode.keySet().forEach(code -> {
            predecessors.put(code, new LinkedHashSet<>());
            successors.put(code, new LinkedHashSet<>());
        });
        boolean hasExplicitRelation = !nodeGraph && routeList.stream().anyMatch(route ->
                route.getPreRouteCode() != null && route.getPreRouteCode().length > 0
                        || route.getNextRouteCode() != null && route.getNextRouteCode().length > 0);
        List<MdmProcessRouteWorkingDTO> legacyOrder = routeList.stream().sorted(Comparator
                .comparingInt((MdmProcessRouteWorkingDTO route) -> Optional.ofNullable(route.getOrderNum())
                        .orElse(Optional.ofNullable(route.getRouteOrder()).orElse(Integer.MAX_VALUE)))
                .thenComparing(route -> nodeGraph ? route.getRouteNodeCode()
                        : StrUtil.blankToDefault(route.getProcessCode(), route.getRouteCode()))).toList();
        if (!hasExplicitRelation && legacyOrder.size() > 1) {
            if (!nodeGraph) {
                for (int index = 1; index < legacyOrder.size(); index++) {
                    legacyOrder.get(index).setPreRouteCode(new String[]{StrUtil.blankToDefault(
                            legacyOrder.get(index - 1).getProcessCode(), legacyOrder.get(index - 1).getRouteCode())});
                }
            }
        }
        byCode.forEach((code, route) -> strictRouteCodes(
                nodeGraph ? route.getPreRouteNodeCodes() : route.getPreRouteCode(),
                nodeGraph ? "前置路线节点" : "前置工序").forEach(predecessor -> {
            if (!byCode.containsKey(predecessor)) {
                throw new CheckedException((nodeGraph ? "节点[" : "工序[") + code + "]引用不存在的"
                        + (nodeGraph ? "前置节点[" : "前置工序[") + predecessor + "]");
            }
            if (code.equals(predecessor)) {
                throw new CheckedException((nodeGraph ? "节点[" : "工序[") + code + "]存在自环");
            }
            predecessors.get(code).add(predecessor);
            successors.get(predecessor).add(code);
        }));
        if (nodeGraph) {
            byCode.forEach((code, route) -> {
                List<String> declared = strictRouteCodes(route.getNextRouteNodeCodes(), "后置路线节点");
                if (!new LinkedHashSet<>(declared).equals(successors.get(code))) {
                    throw new CheckedException("路线节点[" + code + "]前后置关系不是相互逆关系");
                }
            });
        }
        long terminalCount = successors.values().stream().filter(Set::isEmpty).count();
        if (terminalCount != 1) {
            throw new CheckedException("生产工艺路线必须且只能有一道最终工序，禁止下达");
        }
        String terminalCode = successors.entrySet().stream().filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        if (Boolean.TRUE.equals(byCode.get(terminalCode).getOverlapEnabled())) {
            throw new CheckedException("最终路线节点没有后序，不能启用转移批量重叠");
        }
        Map<String, Integer> indegrees = predecessors.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().size(), (a, b) -> a, LinkedHashMap::new));
        Map<String, Integer> levels = new HashMap<>();
        Deque<String> ready = new ArrayDeque<>();
        indegrees.forEach((code, degree) -> {
            if (degree == 0) {
                ready.add(code);
                levels.put(code, 1);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String code = ready.removeFirst();
            visited++;
            for (String successor : successors.get(code)) {
                levels.merge(successor, levels.get(code) + 1, Math::max);
                int remaining = indegrees.computeIfPresent(successor, (key, degree) -> degree - 1);
                if (remaining == 0) {
                    ready.addLast(successor);
                }
            }
        }
        if (visited != routeList.size()) {
            throw new CheckedException("生产工艺路线快照存在环路，禁止下达");
        }
        Map<String, String> timingModes = new HashMap<>();
        byCode.forEach((code, route) -> timingModes.put(code, route.getType()));
        boolean uniqueProcesses = routeList.stream().map(route -> StrUtil.blankToDefault(
                route.getProcessCode(), route.getRouteCode())).distinct().count() == routeList.size();
        Map<String, String> nodeProcesses = nodeGraph ? byCode.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> StrUtil.blankToDefault(entry.getValue().getProcessCode(),
                        entry.getValue().getRouteCode()), (left, right) -> left, LinkedHashMap::new)) : Map.of();
        byCode.forEach((code, route) -> {
            if (nodeGraph) {
                route.setPreRouteNodeCodes(predecessors.get(code).toArray(String[]::new));
                route.setNextRouteNodeCodes(successors.get(code).toArray(String[]::new));
                route.setPreRouteCode(projectProcesses(predecessors.get(code), nodeProcesses));
                route.setNextRouteCode(projectProcesses(successors.get(code), nodeProcesses));
            } else {
                route.setPreRouteCode(predecessors.get(code).toArray(String[]::new));
                route.setNextRouteCode(successors.get(code).toArray(String[]::new));
            }
            route.setOrderNum(levels.get(code));
            route.setRouteOrder(levels.get(code));
            if (nodeGraph) {
                route.setNodeTimingRulesJson(com.kitex.mom.mdm.support.MdmRouteNodeTimingPolicy.normalize(
                        route.getNodeTimingRulesJson(), Map.of(), code,
                        com.kitex.mom.mdm.support.MdmProcessTimingPolicy.ancestors(code, predecessors), timingModes));
                route.setTimingRulesJson(uniqueProcesses
                        ? com.kitex.mom.mdm.support.MdmRouteNodeTimingPolicy.toLegacy(
                        route.getNodeTimingRulesJson(), nodeProcesses) : null);
            } else {
                route.setTimingRulesJson(com.kitex.mom.mdm.support.MdmProcessTimingPolicy
                        .parse(route.getTimingRulesJson()).normalize(code,
                                com.kitex.mom.mdm.support.MdmProcessTimingPolicy.ancestors(code, predecessors)));
                com.kitex.mom.mdm.support.MdmProcessTimingPolicy.parse(route.getTimingRulesJson())
                        .validateModes(code, timingModes);
            }
        });
    }

    private List<String> strictRouteCodes(String[] values, String label) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = StrUtil.trim(value);
            if (StrUtil.isBlank(normalized) || !Objects.equals(value, normalized) || !unique.add(normalized)) {
                throw new CheckedException(label + "包含空值、首尾空格或重复项");
            }
            result.add(normalized);
        }
        return result;
    }

    private String[] projectProcesses(Collection<String> nodes, Map<String, String> nodeProcesses) {
        List<String> result = new ArrayList<>();
        for (String node : nodes) {
            String process = nodeProcesses.get(node);
            if (StrUtil.isBlank(process)) {
                throw new CheckedException("路线节点[" + node + "]缺少主工序投影");
            }
            result.add(process);
        }
        return result.toArray(String[]::new);
    }

    private String joinCodes(String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        return Arrays.stream(values).map(String::trim).filter(StrUtil::isNotBlank).distinct()
                .collect(Collectors.joining(","));
    }

    private List<String> splitCodes(String value) {
        if (StrUtil.isBlank(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    /**
     * 在正式工单产生工序和计划回执前，核对需检节点能在当前组织取得规则和已发布标准。
     * 这里只验证下达条件，实际检验依据仍由 QMS 按原请求建立。
     *
     * @param task 已锁定的草稿工单
     * @param routes 从该工单绑定的工程原版提取的路线节点
     */
    private void verifyInspectionConfiguration(MesTaskEntity task, List<MdmProcessRouteWorkingDTO> routes) {
        if (routes.stream().noneMatch(route -> "1".equals(route.getIsInspection()))) {
            return;
        }
        MesQualityGateSourceEvidence.scope();
        MesInspectionRuleEntity rule = inspectionRuleService.getRuleByMatCode(task.getMatCode());
        if (rule == null || !Objects.equals(rule.getTenantId(), task.getTenantId())
                || !Objects.equals(rule.getFactoryCode(), task.getFactoryCode())
                || !"1".equals(rule.getStatus()) || StrUtil.isBlank(rule.getCode())
                || StrUtil.isBlank(rule.getGateMode()) || StrUtil.isBlank(rule.getInspectionTypes())) {
            throw new CheckedException("产品【" + task.getMatCode() + "】有需检工序，请先在当前工厂配置并启用质检任务生成规则");
        }
        QualityGateModeEnum.fromCode(rule.getGateMode());
        if (Arrays.stream(rule.getInspectionTypes().split(",", -1)).map(String::trim)
                .anyMatch(stage -> Arrays.stream(ProcedureLabelTypeEnum.values())
                        .noneMatch(type -> matchesInspectionType(stage, type)))) {
            throw new CheckedException("产品【" + task.getMatCode() + "】的质检规则包含无效检验阶段，请核对后再下达");
        }
        if (containsInspectionType(rule, ProcedureLabelTypeEnum.MIDDLE)) {
            if (!containsInspectionType(rule, ProcedureLabelTypeEnum.FIRST)) {
                throw new CheckedException("产品【" + task.getMatCode() + "】的中检需要首检，请核对质检规则");
            }
            MesPatrolInterval.validate(rule);
        }
        R<QmsQualityStandardEntity> response;
        try {
            response = remoteQualityStandardService.getRandomStandard(new QmsQualityStandardEntity()
                    .setMatCode(task.getMatCode()).setInspectionType(InspectionTaskTypeEnum.PROCEDURE.name()));
        } catch (RuntimeException failure) {
            throw new CheckedException("暂时无法核对 QMS 工序质检标准，请稍后重试下达", failure);
        }
        if (response == null || response.getCode() != 0) {
            throw new CheckedException("暂时无法核对 QMS 工序质检标准，请稍后重试下达");
        }
        QmsQualityStandardEntity standard = response.getData();
        if (standard == null || standard.getId() == null || standard.getId() <= 0
                || !Integer.valueOf(1).equals(standard.getPublished())
                || !Objects.equals(standard.getTenantId(), task.getTenantId())
                || !Objects.equals(standard.getFactoryCode(), task.getFactoryCode())
                || !Objects.equals(standard.getMatCode(), task.getMatCode())
                || !InspectionTaskTypeEnum.PROCEDURE.name().equals(standard.getInspectionType())) {
            throw new CheckedException("产品【" + task.getMatCode() + "】缺少当前工厂已发布的工序质检标准，请先在 QMS 发布");
        }
    }

    /**
     * 生成质检任务
     *
     * @param saveTaskList 生产任务列表
     * @param matCode      物料编码
     */
    private void genInspectionTask(List<MesProduceTaskEntity> saveTaskList, String matCode) {
        if (saveTaskList.stream().noneMatch(task -> "1".equals(task.getIsInspection()))) {
            return;
        }
        MesInspectionRuleEntity inspectionRule = inspectionRuleService.getRuleByMatCode(matCode);
        LocalDateTime generatedAt = LocalDateTime.now();
        // 同张工单的需检节点使用同一排程基准，并在登记任何首检原命令前核对班次。
        boolean patrol = containsInspectionType(inspectionRule, ProcedureLabelTypeEnum.FIRST)
                && containsInspectionType(inspectionRule, ProcedureLabelTypeEnum.MIDDLE);
        LocalDateTime nextPatrolTime = null;
        String nextPatrolShift = null;
        if (patrol) {
            MesPatrolInterval.validate(inspectionRule);
            if ("HOUR".equals(inspectionRule.getMethod())) {
                nextPatrolTime = MesPatrolInterval.nextTime(generatedAt, inspectionRule.getNum());
            } else {
                int count = MesPatrolInterval.shiftCount(inspectionRule.getNum());
                var shift = MesPatrolInterval.nextShift(remoteClassesService.getClassesBydate(
                        RemoteClassesService.RULE_DATE_TIME_FORMAT.format(generatedAt), count), count, null);
                nextPatrolShift = shift.code();
                nextPatrolTime = shift.startsAt();
            }
        }
        LocalDateTime scheduledTime = nextPatrolTime;
        String scheduledShift = nextPatrolShift;
        List<MesInspectionGenRecordEntity> recordList = new ArrayList<>();
        List<MesInspectionGenRecordEntity> delivery = new ArrayList<>();
        // 生成质检任务
        saveTaskList.forEach(
                task -> {
                    // 需要质检
                    if ("1".equals(task.getIsInspection())) {
                        // 设置了规则才创建
                        if (Objects.nonNull(inspectionRule)
                                && containsInspectionType(
                                        inspectionRule, ProcedureLabelTypeEnum.FIRST)) {
                            String sourceRequestId = "MES_QMS_V2:" + task.getCode() + ":FIRST";
                            boolean prepared = false;
                            String remark = "等待工单提交后生成首检";
                            String syncMessage = remark;
                            try {
                                QmsQualityStandardEntity standard =
                                        remoteQualityStandardService
                                                .getRandomStandard(
                                                        new QmsQualityStandardEntity()
                                                                .setMatCode(task.getMatCode())
                                                                .setInspectionType(
                                                                        InspectionTaskTypeEnum
                                                                                .PROCEDURE
                                                                                .name()))
                                                .getData();
                                if (standard == null) {
                                    throw new CheckedException("未匹配到已发布的工序质检标准");
                                }
                                InspectionProcedureCreateParam createParam =
                                        buildCreateParam(task, standard, sourceRequestId);
                                MesQualityGateSourceEvidence.scope();
                                if (!Objects.equals(
                                                task.getTenantId(),
                                                TenantContextHolder.getTenantId())
                                        || !Objects.equals(
                                                task.getFactoryCode(),
                                                FactoryContextHolder.getFactoryCode()))
                                    throw new CheckedException("首检来源与实际组织不一致");
                                // Register the exact original independently, but never send before
                                // source commit.
                                qmsInspectionCommands.register(createParam, task.getFactoryCode());
                                prepared = true;
                            } catch (Exception exception) {
                                remark = "首检任务准备失败，请核对原号和质检配置";
                                syncMessage = rootMessage(exception);
                            }
                            MesInspectionGenRecordEntity record =
                                    new MesInspectionGenRecordEntity();
                            record.setTaskCode(task.getTaskCode());
                            record.setProduceTaskCode(task.getCode());
                            record.setSourceRequestId(sourceRequestId);
                            record.setRuleCode(inspectionRule.getCode());
                            record.setRuleName(inspectionRule.getName());
                            record.setInspectionType(ProcedureLabelTypeEnum.FIRST.name());
                            record.setGenFlag("0");
                            record.setGenRemark(remark);
                            record.setGateStatus(
                                    prepared
                                            ? QualityGateStatusEnum.PENDING.name()
                                            : QualityGateStatusEnum.ERROR.name());
                            record.setSyncMessage(syncMessage);
                            record.setBlockedCount(0);
                            record.setGenTime(generatedAt);
                            try {
                                record.setGenShiftCode(
                                        remoteClassesService
                                                .getRuleBanci(generatedAt)
                                                .getData());
                            } catch (Exception exception) {
                                record.setSyncMessage(StrUtil.subPre(
                                        syncMessage + "；班次读取失败：" + rootMessage(exception), 1000));
                            }
                            record.setNextTime(scheduledTime);
                            record.setNextShift(scheduledShift);
                            record.setFactoryCode(task.getFactoryCode());
                            record.setTenantId(task.getTenantId());
                            record.setDelFlag("0");
                            recordList.add(record);
                            if (prepared) delivery.add(record);
                        }
                    }
                });
        for (MesInspectionGenRecordEntity record : recordList) {
            if (!genRecordService.save(record) || record.getId() == null || record.getId() <= 0)
                throw new CheckedException("首检待生成记录未完整保存，工单下达回滚");
        }
        if (!delivery.isEmpty()) inspectionGeneration.afterCommit(delivery);
    }

    /**
     * 生产计划模块修改对应的生产排产状态
     */
    private void planMesStatusUpdate(MesTaskEntity mesTask) {
        List<MesApsBatchAllocationEntity> allocations = batchAllocations(mesTask, false);
        List<OmsOrderLifeCycleDTO> callbacks = new ArrayList<>();
        if (!allocations.isEmpty()) {
            for (MesApsBatchAllocationEntity allocation : allocations) {
                callbacks.add(lifeCycleCallback(mesTask, allocation.getIssueNo(), allocation.getIssueId(),
                        "ISSUE", "2", OrderLongStateEnum.SCHEDULE.getCode(), null, null,
                        String.format("APS批次【%s】生成MES工单【%s】并已排产，来源分配量【%s】，操作时间【%s】",
                                mesTask.getApsBatchNo(), mesTask.getCode(), allocation.getAllocatedQuantity(), LocalDate.now())));
            }
        } else {
            callbacks.add(lifeCycleCallback(mesTask, mesTask.getPlanNo(), null, "ISSUE", "2",
                    OrderLongStateEnum.SCHEDULE.getCode(), null, null,
                    String.format("工单编号【%s】已排产,操作时间【%s】", mesTask.getCode(), LocalDate.now())));
        }
        Boolean statusUpdate = remotePlnIssueService.edit(callbacks).getData();
        if (statusUpdate == null || !statusUpdate) {
            throw new CheckedException(String.format("%s的工单推送生产计划模块失败", mesTask.getCode()));
        }
    }

	/**
	 * 只作废尚未下达且无关联事实的独立手工草稿，保留原建单回执与全部完成事实。
	 * 既有CLOSE只作当前终态观察；计划/返工/已执行来源必须走各自正式撤回或停止流程。
	 *
	 * @param id 当前租户实际工厂内的准确工单ID
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public void close(Long id) {
		MesTaskEntity current = taskCommands.lock(id);
		if (MesTaskStatusEnum.CLOSE.name().equals(current.getStatus())) {
			return;
		}
		taskCommands.requireIndependentDraft(current);
		int changed = baseMapper.update(null, draftCondition(current)
				.eq(MesTaskEntity::getSourceRequestId, current.getSourceRequestId())
				.set(MesTaskEntity::getStatus, MesTaskStatusEnum.CLOSE.name())
				.set(MesTaskEntity::getUpdateBy, SecurityUtils.getAuthentication().getName())
				.set(MesTaskEntity::getUpdateTime, LocalDateTime.now()));
		if (changed != 1) {
			throw new CheckedException("手工草稿作废未命中原状态，已回滚");
		}
	}

    /**
     * 生产工单对接生产计划接口
     *
     * @param planMesQueryDTO 查询条件
     * @return R
     */
    @Override
    public List<MesTaskEntity> planMesList(PlanMesQueryDTO planMesQueryDTO) {
        QueryWrapper<MesTaskEntity> wrapper = Wrappers.query();
        wrapper.select("notice_num - COALESCE(complete_num,0) as completeNum", "plan_no");
        wrapper.lambda().in(ObjUtil.isNotEmpty(planMesQueryDTO.getMatCodes()), MesTaskEntity::getMatCode, planMesQueryDTO.getMatCodes())
                .le(MesTaskEntity::getStartTime, planMesQueryDTO.getTime())
                .eq(MesTaskEntity::getStatus, MesTaskStatusEnum.PRODUCT.name())
                .isNotNull(MesTaskEntity::getPlanNo);
        return this.list(wrapper);
    }

    /**
     * 根据生产订单号查询生产工单最后一道工序的报工记录
     *
     * @param orders 生产订单列表
     * @return R
     */
    @Override
    public List<MesTaskReportOmsVO> getByOrders(List<String> orders) {
        List<MesTaskReportOmsVO> reportOmsVOList = new ArrayList<>();
        if (CollUtil.isEmpty(orders)) {
            return reportOmsVOList;
        }
        LinkedHashMap<String, MesTaskEntity> taskMap = this.list(new LambdaQueryWrapper<MesTaskEntity>()
                .in(MesTaskEntity::getPlanNo, orders)).stream().collect(Collectors.toMap(MesTaskEntity::getCode,
                Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<Long> batchTaskIds = apsBatchAllocationMapper.selectList(
                        new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                                .in(MesApsBatchAllocationEntity::getIssueNo, orders))
                .stream().map(MesApsBatchAllocationEntity::getMesTaskId).filter(Objects::nonNull).distinct().toList();
        if (!batchTaskIds.isEmpty()) {
            this.listByIds(batchTaskIds).forEach(task -> taskMap.putIfAbsent(task.getCode(), task));
        }
        Map<Long, List<MesApsBatchAllocationEntity>> allocationsByTask = taskMap.values().isEmpty()
                ? Collections.emptyMap()
                : apsBatchAllocationMapper.selectList(new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                        .in(MesApsBatchAllocationEntity::getMesTaskId,
                                taskMap.values().stream().map(MesTaskEntity::getId).toList())
                        .orderByAsc(MesApsBatchAllocationEntity::getAllocationSeq)).stream()
                .collect(Collectors.groupingBy(MesApsBatchAllocationEntity::getMesTaskId,
                        LinkedHashMap::new, Collectors.toList()));
        if (CollectionUtil.isNotEmpty(taskMap)) {
            MPJLambdaWrapper<MesTaskReportEntity> wrapper = new MPJLambdaWrapper<MesTaskReportEntity>()
                    .selectAll(MesTaskReportEntity.class)
                    .select(MesTaskArrangeEntity::getRouteName, MesTaskArrangeEntity::getRouteCode, MesTaskArrangeEntity::getWorksiteCodes,
                            MesTaskArrangeEntity::getWorksiteNames, MesTaskArrangeEntity::getWorkshopCode, MesTaskArrangeEntity::getWorkshopName)
                    .innerJoin(MesTaskArrangeEntity.class, MesTaskArrangeEntity::getCode, MesTaskReportEntity::getArrangeCode)
                    .eq(MesTaskArrangeEntity::getLastRoute, "1")
                    .in(MesTaskArrangeEntity::getTaskCode, taskMap.keySet());
            List<MesTaskReportOmsVO> reportList = mesTaskReportMapper.selectJoinList(MesTaskReportOmsVO.class, wrapper);
            for (MesTaskReportOmsVO report : reportList) {
                MesTaskEntity mesTask = taskMap.get(report.getTaskCode());
                List<MesApsBatchAllocationEntity> allocations = allocationsByTask.getOrDefault(
                        mesTask.getId(), Collections.emptyList());
                if (allocations.isEmpty()) {
                    report.setPlanNo(mesTask.getPlanNo());
                    report.setNoticeNum(mesTask.getNoticeNum());
                    report.setStatus(mesTask.getStatus());
                    reportOmsVOList.add(report);
                    continue;
                }
                for (MesApsBatchAllocationEntity allocation : allocations) {
                    if (!orders.contains(allocation.getIssueNo())) continue;
                    MesTaskReportOmsVO sourceReport = new MesTaskReportOmsVO();
                    BeanUtil.copyProperties(report, sourceReport);
                    sourceReport.setPlanNo(allocation.getIssueNo());
                    sourceReport.setApsBatchNo(mesTask.getApsBatchNo());
                    sourceReport.setSourceAllocatedQuantity(allocation.getAllocatedQuantity());
                    sourceReport.setNoticeNum(allocation.getAllocatedQuantity());
                    sourceReport.setQualifiedNum(apportionReportQuantity(report.getQualifiedNum(), allocations, allocation));
                    sourceReport.setUnPassNum(apportionReportQuantity(report.getUnPassNum(), allocations, allocation));
                    sourceReport.setStatus(mesTask.getStatus());
                    reportOmsVOList.add(sourceReport);
                }
            }
        }
        return reportOmsVOList;
    }

    private BigDecimal apportionReportQuantity(BigDecimal quantity,
                                               List<MesApsBatchAllocationEntity> allocations,
                                               MesApsBatchAllocationEntity target) {
        if (quantity == null) return null;
        BigDecimal total = allocations.stream().map(MesApsBatchAllocationEntity::getAllocatedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal assigned = BigDecimal.ZERO;
        for (int index = 0; index < allocations.size(); index++) {
            MesApsBatchAllocationEntity current = allocations.get(index);
            BigDecimal value = index == allocations.size() - 1 ? quantity.subtract(assigned)
                    : quantity.multiply(current.getAllocatedQuantity()).divide(total, 8, RoundingMode.HALF_UP);
            if (Objects.equals(current.getId(), target.getId())) return value;
            assigned = assigned.add(value);
        }
        return BigDecimal.ZERO;
    }

    /**
     * 生产工单入库
     *
     * @param mesTaskInWmsDTO 生产工单入库DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Boolean inWms(MesTaskInWmsDTO mesTaskInWmsDTO) {
        String actualFactory = factoryAccess.require("write");
        if (mesTaskInWmsDTO == null || mesTaskInWmsDTO.getId() == null
                || mesTaskInWmsDTO.getInWmsNum() == null || mesTaskInWmsDTO.getInWmsNum().signum() <= 0
                || StrUtil.isBlank(mesTaskInWmsDTO.getWarehouseCode())
                || StrUtil.isBlank(mesTaskInWmsDTO.getRequestId())) {
            throw new CheckedException("入库工单、仓库、正数入库数量和永久请求号不能为空");
        }
        String requestId = StrUtil.trim(mesTaskInWmsDTO.getRequestId());
        if (requestId.length() > 64) throw new CheckedException("入库幂等号长度不能超过64");
        String fingerprint = inWmsFingerprint(mesTaskInWmsDTO);
        taskInWmsReceiptMapper.lockRequest(requestId);
        if (isInWmsV2(mesTaskInWmsDTO)) {
            MesTaskInWmsCommandEntity original = taskInWmsCommands.original(mesTaskInWmsDTO);
            if (original == null || "CANCELLED_BEFORE_DISPATCH".equals(original.getStatus())) {
                throw new CheckedException("原入库命令未登记或已经结束，禁止执行");
            }
        }
        MesTaskInWmsReceiptEntity existing = taskInWmsReceiptMapper.permanentRequest(
                com.kitex.common.data.tenant.TenantContextHolder.getTenantId(), requestId);
        if (existing != null) {
            assertInWmsReceipt(existing, mesTaskInWmsDTO, actualFactory, fingerprint);
            return true;
        }
        String expectedSourceFingerprint = StrUtil.trim(mesTaskInWmsDTO.getExpectedSourceFingerprint());
        if (expectedSourceFingerprint == null || expectedSourceFingerprint.length() != 64) {
            throw new CheckedException("请先核验本次入库对应的末工序报工来源");
        }
        MesTaskEntity initial = Optional.ofNullable(this.getById(mesTaskInWmsDTO.getId()))
                .orElseThrow(() -> new CheckedException("工单查询失败"));
        if (!Objects.equals(initial.getFactoryCode(), actualFactory)) {
            throw new CheckedException("生产工单不属于当前实际工厂");
        }
        if (StrUtil.isNotBlank(initial.getApsBatchNo())) {
            baseMapper.acquireApsBatchTransactionLock(initial.getApsBatchNo());
        }
        MesTaskEntity mesTask = Optional.ofNullable(this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                        .eq(MesTaskEntity::getId, mesTaskInWmsDTO.getId())
                        .eq(MesTaskEntity::getFactoryCode, actualFactory).last("FOR UPDATE"), false))
                .orElseThrow(() -> new CheckedException("工单查询失败"));
        // 处理待入库数量
        BigDecimal unInStockNum = Optional.ofNullable(mesTask.getUnInStockNum()).orElseThrow(() -> new CheckedException("待入库数量不存在"));
        // 检查入库数量是否超过待入库数量
        if (mesTaskInWmsDTO.getInWmsNum().compareTo(unInStockNum) > 0) {
            throw new CheckedException("入库数量大于工单待入库数量");
        }
        List<InboundReportAllocation> reportAllocations = allocateInboundReports(
                mesTask, mesTaskInWmsDTO.getInWmsNum(), true);
        String actualSourceFingerprint = inWmsAllocationFingerprint(
                mesTask, mesTaskInWmsDTO.getInWmsNum(), reportAllocations);
        if (!Objects.equals(expectedSourceFingerprint, actualSourceFingerprint)) {
            throw new CheckedException("入库来源在核验后已变化，请重新核验末工序报工来源");
        }
        // 更新待入库数量和已入库数量
        mesTask.setUnInStockNum(unInStockNum.subtract(mesTaskInWmsDTO.getInWmsNum()));
        mesTask.setInStockNum(Optional.ofNullable(mesTask.getInStockNum()).orElse(BigDecimal.ZERO).add(mesTaskInWmsDTO.getInWmsNum()));

        // 推送仓库
        this.wmsProductInNotice(mesTask, mesTaskInWmsDTO, reportAllocations);
        // 推送生产计划模块
        if (StrUtil.isNotBlank(mesTask.getPlanNo())) {
            planMesFinishUpdate(mesTask, mesTaskInWmsDTO);
        }
        // 更新工单信息
        boolean updated = this.updateById(mesTask);
        if (!updated) {
            // WMS可能已经提交；不能正常返回false并提交本地部分变更，更不能提示换号重做。
            throw new CheckedException("工单入库结果未能在MES落账，请保留原请求号核对，不要另号重复提交");
        }
        if (updated) {
            MesTaskInWmsReceiptEntity receipt = new MesTaskInWmsReceiptEntity()
                    .setRequestId(requestId).setRequestFingerprint(inWmsFingerprint(mesTaskInWmsDTO))
                    .setMesTaskId(mesTask.getId()).setMesTaskCode(mesTask.getCode())
                    .setApsBatchNo(mesTask.getApsBatchNo()).setWarehouseCode(mesTaskInWmsDTO.getWarehouseCode())
                    .setInStockQuantity(mesTaskInWmsDTO.getInWmsNum()).setHandledTime(LocalDateTime.now())
                    .setFactoryCode(mesTask.getFactoryCode());
            if (taskInWmsReceiptMapper.insert(receipt) != 1) {
                throw new CheckedException("成品入库永久回执保存失败，已回滚本次业务事务");
            }
            for (InboundReportAllocation allocation : reportAllocations) {
                MesTaskReportEntity report = allocation.report();
                MesTaskInWmsReceiptDetailEntity detail = new MesTaskInWmsReceiptDetailEntity()
                        .setReceiptId(receipt.getId()).setReportId(report.getId())
                        .setReportRequestId(report.getRequestId()).setTaskCode(report.getTaskCode())
                        .setArrangeCode(report.getArrangeCode()).setProduceTaskCode(report.getProduceTaskCode())
                        .setAllocatedQuantity(allocation.quantity())
                        .setQualityQuantityStatus(report.getQualityQuantityStatus())
                        .setQualityTaskIds(report.getQualityTaskIds())
                        .setQualityEvidenceFingerprint(report.getQualityEvidenceFingerprint())
                        .setQualityEvidenceSnapshot(report.getQualityEvidenceSnapshot())
                        .setReportTime(report.getReportTime())
                        .setSourceFingerprint(inWmsReportSourceFingerprint(report))
                        .setFactoryCode(mesTask.getFactoryCode());
                if (taskInWmsReceiptDetailMapper.insert(detail) != 1) {
                    throw new CheckedException("成品入库报工来源分配保存失败，已回滚本次业务事务");
                }
            }
        }
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public MesTaskInWmsSourceDTO previewInWmsSource(MesTaskInWmsDTO command) {
        String actualFactory = factoryAccess.require("read");
        if (command == null || command.getId() == null || command.getInWmsNum() == null
                || command.getInWmsNum().signum() <= 0) {
            throw new CheckedException("请选择生产工单并填写正数入库数量");
        }
        exactWmsInboundQuantity(command.getInWmsNum());
        MesTaskEntity task = Optional.ofNullable(this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                        .eq(MesTaskEntity::getId, command.getId())
                        .eq(MesTaskEntity::getFactoryCode, actualFactory), false))
                .orElseThrow(() -> new CheckedException("当前实际工厂内不存在该生产工单"));
        BigDecimal unInStock = Objects.requireNonNullElse(task.getUnInStockNum(), BigDecimal.ZERO);
        if (command.getInWmsNum().compareTo(unInStock) > 0) {
            throw new CheckedException("预览入库数量大于工单待入库数量");
        }
        List<InboundReportAllocation> allocations = allocateInboundReports(task, command.getInWmsNum(), false);
        String reportUnit = inboundReportUnit(task, allocations);
        List<MesTaskInWmsSourceDTO.Line> lines = allocations.stream()
                .map(this::toInWmsSourceLine).toList();
        String evidence = inWmsAllocationFingerprint(task, command.getInWmsNum(), allocations);
        return new MesTaskInWmsSourceDTO().setTaskId(task.getId()).setTaskCode(task.getCode())
                .setRequestQuantity(command.getInWmsNum()).setReportUnit(reportUnit)
                .setCurrentInStockQuantity(task.getInStockNum())
                .setCurrentTrackedQuantity(Objects.requireNonNullElse(task.getInStockNum(), BigDecimal.ZERO))
                .setEvidenceFingerprint(evidence).setLines(lines);
    }

    /**
     * 将本次成品入库严格按末工序有效报工的可入库数量FIFO分配。
     * 历史已入库数量若没有同额来源明细，禁止继续制造新的伪精确链路。
     */
    private List<InboundReportAllocation> allocateInboundReports(MesTaskEntity task, BigDecimal inboundQuantity,
                                                                 boolean lock) {
        List<MesTaskInWmsReceiptDetailEntity> existingDetails =
                inboundAllocationService.activeByTask(task.getCode(), task.getFactoryCode(), lock);
        BigDecimal trackedInbound = existingDetails.stream()
                .map(MesTaskInWmsReceiptDetailEntity::getAllocatedQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal taskInbound = Objects.requireNonNullElse(task.getInStockNum(), BigDecimal.ZERO);
        if (trackedInbound.compareTo(taskInbound) != 0) {
            throw new CheckedException("工单已有成品入库量缺少等额报工来源明细；须先治理历史归属，禁止继续形成伪精确链路");
        }

        List<MesTaskArrangeEntity> finalArranges = mesTaskArrangeMapper.selectList(
                new LambdaQueryWrapper<MesTaskArrangeEntity>()
                        .eq(MesTaskArrangeEntity::getTaskCode, task.getCode())
                        .eq(MesTaskArrangeEntity::getFactoryCode, task.getFactoryCode())
                        .eq(MesTaskArrangeEntity::getLastRoute, true)
                        .orderByAsc(MesTaskArrangeEntity::getId)
                        .last(lock ? "LIMIT 1001 FOR UPDATE" : "LIMIT 1001"));
        if (finalArranges.isEmpty()) {
            throw new CheckedException("生产工单缺少明确末工序派工，不能把入库数量归属到具体报工");
        }
        if (finalArranges.size() > 1000) {
            throw new CheckedException("生产工单末工序派工超过1000条，禁止截断入库来源");
        }
        List<String> arrangeCodes = finalArranges.stream().map(MesTaskArrangeEntity::getCode)
                .filter(StrUtil::isNotBlank).distinct().toList();
        if (arrangeCodes.size() != finalArranges.size()) {
            throw new CheckedException("末工序派工编码为空或重复，不能建立成品入库来源");
        }
        List<MesTaskReportEntity> reports = mesTaskReportMapper.selectList(
                new LambdaQueryWrapper<MesTaskReportEntity>()
                        .eq(MesTaskReportEntity::getTaskCode, task.getCode())
                        .eq(MesTaskReportEntity::getFactoryCode, task.getFactoryCode())
                        .in(MesTaskReportEntity::getArrangeCode, arrangeCodes)
                        .eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name())
                        .orderByAsc(MesTaskReportEntity::getReportTime)
                        .orderByAsc(MesTaskReportEntity::getId)
                        .last(lock ? "LIMIT 5001 FOR UPDATE" : "LIMIT 5001"));
        if (reports.size() > 5000) {
            throw new CheckedException("末工序有效报工超过5000条，禁止截断成品入库来源");
        }
        Map<Long, BigDecimal> allocatedByReport = existingDetails.stream()
                .collect(Collectors.groupingBy(MesTaskInWmsReceiptDetailEntity::getReportId,
                        Collectors.reducing(BigDecimal.ZERO,
                                item -> Objects.requireNonNullElse(item.getAllocatedQuantity(), BigDecimal.ZERO),
                                BigDecimal::add)));
        BigDecimal remaining = inboundQuantity;
        List<InboundReportAllocation> result = new ArrayList<>();
        for (MesTaskReportEntity report : reports) {
            BigDecimal eligible = QualityQuantityStatusEnum.NOT_REQUIRED.name().equals(report.getQualityQuantityStatus())
                    ? Objects.requireNonNullElse(report.getReportNum(), BigDecimal.ZERO)
                    : Objects.requireNonNullElse(report.getQualityReleasedNum(), BigDecimal.ZERO);
            BigDecimal allocated = allocatedByReport.getOrDefault(report.getId(), BigDecimal.ZERO);
            if (allocated.signum() < 0 || allocated.compareTo(eligible) > 0) {
                throw new CheckedException("报工[" + report.getId() + "]入库来源数量大于当前质量可入库数量，请先对账");
            }
            BigDecimal available = eligible.subtract(allocated);
            if (available.signum() <= 0) continue;
            BigDecimal use = available.min(remaining);
            if (use.signum() > 0) result.add(new InboundReportAllocation(report, use));
            remaining = remaining.subtract(use);
            if (remaining.signum() == 0) break;
        }
        if (remaining.signum() > 0) {
            throw new CheckedException("末工序有效且质量可用的未入库报工数量不足，禁止用工单汇总量猜测来源");
        }
        return result;
    }

    /**
     * 核对本次实际分配的末序报工单位，预览与发送入库通知使用同一判定。
     * 原报工缺单位、混用单位或与当前工厂库存记账单位冲突时，保留原数量并要求核实。
     */
    private String inboundReportUnit(MesTaskEntity task, List<InboundReportAllocation> allocations) {
        String unit = null;
        for (InboundReportAllocation allocation : allocations) {
            MesTaskReportEntity report = allocation.report();
            if (StrUtil.isNotBlank(report.getMatCode()) && !Objects.equals(task.getMatCode(), report.getMatCode())) {
                throw new CheckedException("末序报工 " + report.getId() + " 的产出物料与工单不一致，请核对原报工");
            }
            if (StrUtil.isBlank(report.getReportUnit())
                    || (unit != null && !unit.equals(report.getReportUnit()))) {
                throw new CheckedException("末序报工 " + report.getId() + " 缺少单位或与本次其他来源单位不一致，请核对原报工");
            }
            unit = report.getReportUnit();
        }
        var response = matService.getDetails(task.getMatCode());
        MdmMaterialEntity material = response == null ? null : response.getData();
        if (response == null || !response.isOk() || material == null
                || !Objects.equals(task.getTenantId(), material.getTenantId())
                || !Objects.equals(task.getFactoryCode(), material.getFactoryCode())
                || !Objects.equals(task.getMatCode(), material.getMaterialCode())) {
            throw new CheckedException("未取得工单所属工厂的产出物料，请核对物料档案");
        }
        if (unit == null || !unit.equals(material.getBasicUnit())) {
            throw new CheckedException("工单 " + task.getCode() + " 的原报工单位与物料基本单位不一致，请核实后入库");
        }
        return unit;
    }

    private String inWmsReportSourceFingerprint(MesTaskReportEntity report) {
        return sha256(String.join("|", String.valueOf(report.getId()), Objects.toString(report.getRequestId(), ""),
                Objects.toString(report.getStatus(), ""), Objects.toString(report.getTaskCode(), ""),
                Objects.toString(report.getProduceTaskCode(), ""), Objects.toString(report.getArrangeCode(), ""),
                Objects.toString(report.getReportNum(), ""), Objects.toString(report.getQualityQuantityStatus(), ""),
                Objects.toString(report.getQualityReleasedNum(), ""),
                Objects.toString(report.getQualityTaskIds(), ""),
                Objects.toString(report.getQualityEvidenceFingerprint(), ""),
                Objects.toString(report.getReportTime(), "")));
    }

    /**
     * 将用户看见的来源分配冻结为确定性指纹；提交事务在数据库锁内使用同一算法重算。
     */
    private String inWmsAllocationFingerprint(MesTaskEntity task, BigDecimal quantity,
                                              List<InboundReportAllocation> allocations) {
        StringBuilder evidence = new StringBuilder()
                .append(task.getId()).append('|').append(task.getCode()).append('|')
                .append(quantity.stripTrailingZeros().toPlainString());
        for (InboundReportAllocation allocation : allocations) {
            MesTaskReportEntity report = allocation.report();
            evidence.append('|').append(report.getId())
                    .append(':').append(Objects.toString(report.getRequestId(), ""))
                    .append(':').append(inWmsReportSourceFingerprint(report))
                    .append(':').append(allocation.quantity().stripTrailingZeros().toPlainString());
        }
        return sha256(evidence.toString());
    }

    private MesTaskInWmsSourceDTO.Line toInWmsSourceLine(InboundReportAllocation allocation) {
        MesTaskReportEntity report = allocation.report();
        return new MesTaskInWmsSourceDTO.Line().setReportId(report.getId())
                .setReportRequestId(report.getRequestId()).setArrangeCode(report.getArrangeCode())
                .setProduceTaskCode(report.getProduceTaskCode()).setQuantity(allocation.quantity())
                .setQualityQuantityStatus(report.getQualityQuantityStatus())
                .setQualityTaskIds(report.getQualityTaskIds())
                .setQualityEvidenceFingerprint(report.getQualityEvidenceFingerprint())
                .setReportTime(report.getReportTime())
                .setSourceFingerprint(inWmsReportSourceFingerprint(report));
    }

    /**
     * 根据生产计划单号查询生产工单
     *
     * @param planNo 生产计划单号
     * @return R
     */
    @Override
    public List<MesTaskEntity> omsLife(String planNo) {
        LinkedHashMap<Long, MesTaskEntity> result = this.list(new LambdaQueryWrapper<MesTaskEntity>()
                        .eq(MesTaskEntity::getPlanNo, planNo)).stream()
                .collect(Collectors.toMap(MesTaskEntity::getId, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        List<Long> batchTaskIds = apsBatchAllocationMapper.selectList(
                        new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                                .eq(MesApsBatchAllocationEntity::getIssueNo, planNo))
                .stream().map(MesApsBatchAllocationEntity::getMesTaskId).filter(Objects::nonNull).distinct().toList();
        if (!batchTaskIds.isEmpty()) {
            this.listByIds(batchTaskIds).forEach(task -> result.putIfAbsent(task.getId(), task));
        }
        return new ArrayList<>(result.values());
    }

    /**
     * 推送仓库
     *
     * @param task            工单信息
     * @param mesTaskInWmsDTO 入库信息
     */
    private void wmsProductInNotice(MesTaskEntity task, MesTaskInWmsDTO mesTaskInWmsDTO,
                                    List<InboundReportAllocation> reportAllocations) {
        String reportUnit = inboundReportUnit(task, reportAllocations);
        // 主表处理逻辑
        double inNum = exactWmsInboundQuantity(mesTaskInWmsDTO.getInWmsNum());
        WmsInNoticeEntity inNotice = new WmsInNoticeEntity();
        if (StrUtil.isNotBlank(mesTaskInWmsDTO.getRequestId())) {
            // 新发送域不混用旧请求，避免把旧远端在途补造为“从未发送”。
            String prefix = isInWmsV2(mesTaskInWmsDTO) ? "MES_TASK_IN_WMS_V2:" : "MES_TASK_IN_WMS:";
            inNotice.setSourceRequestId(prefix + mesTaskInWmsDTO.getRequestId().trim());
            inNotice.setSourceRequestFingerprint(inWmsFingerprint(mesTaskInWmsDTO));
        }
        inNotice.setNoticeInNum(inNum);
        inNotice.setAllInNum(0d);
        inNotice.setBillType(InBillTypeEnum.PRODUCT.getCode());
        inNotice.setDocumentTable(DocumentEnum.MES_TASK.getCode());
        inNotice.setDocumentId(task.getId());
        inNotice.setWarehouseCode(mesTaskInWmsDTO.getWarehouseCode());
        inNotice.setWarehouseName(mesTaskInWmsDTO.getWarehouseName());
        inNotice.setRemark(mesTaskInWmsDTO.getRemark());
        inNotice.setFactoryCode(task.getFactoryCode());
        // 明细表处理逻辑
        WmsInNoticeDetailEntity inNoticeDetail = new WmsInNoticeDetailEntity();
        inNoticeDetail.setMatCode(task.getMatCode());
        inNoticeDetail.setMatName(task.getMatName());
        inNoticeDetail.setMatSpec(task.getMatSpec());
        inNoticeDetail.setMatUnit(reportUnit);
        inNoticeDetail.setWarehouseCode(mesTaskInWmsDTO.getWarehouseCode());
        inNoticeDetail.setWarehouseName(mesTaskInWmsDTO.getWarehouseName());
        inNoticeDetail.setNoticeInNum(inNum);
        inNoticeDetail.setInNum(0d);
        inNoticeDetail.setNotInNum(inNum);
        inNoticeDetail.setOperateNum(inNum);
        inNoticeDetail.setFactoryCode(task.getFactoryCode());
        QualityReceiptEvidence qualityEvidence = qualityReceiptEvidence(reportAllocations);
        inNoticeDetail.setSourceQualityTaskIds(qualityEvidence.taskIdsJson());
        inNoticeDetail.setSourceQualityEvidenceFingerprint(qualityEvidence.fingerprint());
        inNoticeDetail.setSourceQualityEvidenceSnapshot(qualityEvidence.snapshotJson());
        inNotice.setDetailList(Collections.singletonList(inNoticeDetail));
        if (isInWmsV2(mesTaskInWmsDTO)) {
            // 先独立提交；WMS超时、PLAN失败或MES回滚都不能抹去曾开始发送的事实。
            taskInWmsCommands.markOutbound(mesTaskInWmsDTO);
        }
        R<Boolean> response = inNoticeService.save(inNotice);
        if (response == null || response.getCode() != 0 || !Boolean.TRUE.equals(response.getData())) {
            // 只有明确业务成功才继续PLAN回传与MES入库回执；响应失败不证明WMS未执行。
            throw new CheckedException("WMS未明确确认入库通知成功，请保留原请求号核对，不要另号重复提交");
        }
    }

    /**
     * 汇总本次成品入库实际消耗的报工质量证据。新链路不允许用工单、物料或批次
     * 反查当前QMS任务，因为当前结论可能已在报工后发生变化。
     */
    private QualityReceiptEvidence qualityReceiptEvidence(List<InboundReportAllocation> allocations) {
        SortedSet<Long> taskIds = new TreeSet<>();
        List<Map<String, Object>> reportFacts = new ArrayList<>();
        for (InboundReportAllocation allocation : allocations) {
            MesTaskReportEntity report = allocation.report();
            if (StrUtil.isBlank(report.getQualityEvidenceFingerprint())
                    || report.getQualityEvidenceFingerprint().length() != 64
                    || StrUtil.isBlank(report.getQualityEvidenceSnapshot())
                    || StrUtil.isBlank(report.getQualityTaskIds())) {
                throw new CheckedException("报工[" + report.getId()
                        + "]缺少业务发生时冻结的QMS质量证据；历史报工禁止按当前任务猜测来源");
            }
            List<Long> reportTaskIds;
            try {
                reportTaskIds = JSONUtil.toList(report.getQualityTaskIds(), Long.class);
            } catch (Exception exception) {
                throw new CheckedException("报工[" + report.getId() + "]的QMS任务来源格式损坏");
            }
            if (!QualityQuantityStatusEnum.NOT_REQUIRED.name().equals(report.getQualityQuantityStatus())
                    && reportTaskIds.isEmpty()) {
                throw new CheckedException("报工[" + report.getId()
                        + "]已使用质量放行数量但没有具体QMS任务来源");
            }
            taskIds.addAll(reportTaskIds);
            if (taskIds.size() > 1000) {
                throw new CheckedException("本次成品入库关联QMS任务超过1000个安全上限，禁止截断质量来源");
            }
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("reportId", report.getId());
            fact.put("allocatedQuantity", allocation.quantity().stripTrailingZeros().toPlainString());
            fact.put("qualityQuantityStatus", report.getQualityQuantityStatus());
            fact.put("qualityTaskIds", reportTaskIds);
            fact.put("qualityEvidenceFingerprint", report.getQualityEvidenceFingerprint());
            reportFacts.add(fact);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("version", "MES_WMS_QMS_RECEIPT_EVIDENCE_V1");
        snapshot.put("reports", reportFacts);
        String snapshotJson = JSONUtil.toJsonStr(snapshot);
        if (snapshotJson.length() > 1_000_000) {
            throw new CheckedException("本次成品入库质量证据超过1MB安全上限，请拆分入库批次");
        }
        return new QualityReceiptEvidence(JSONUtil.toJsonStr(taskIds), sha256(snapshotJson), snapshotJson);
    }

    /**
     * 推送生产计划模块
     *
     * @param task            生产工单
     * @param mesTaskInWmsDTO 入库信息
     */
    private void planMesFinishUpdate(MesTaskEntity task, MesTaskInWmsDTO mesTaskInWmsDTO) {
        List<MesApsBatchAllocationEntity> allocations = batchAllocations(task, true);
        List<OmsOrderLifeCycleDTO> callbacks = new ArrayList<>();
        if (!allocations.isEmpty()) {
            BigDecimal totalAllocated = allocations.stream().map(MesApsBatchAllocationEntity::getAllocatedQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal oldTotal = allocations.stream().map(item -> Objects.requireNonNullElse(
                    item.getInStockQuantity(), BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal newTotal = oldTotal.add(mesTaskInWmsDTO.getInWmsNum());
            if (newTotal.compareTo(totalAllocated) > 0) {
                throw new CheckedException("APS批次累计入库量超过来源计划分配总量");
            }
            BigDecimal assignedTarget = BigDecimal.ZERO;
            for (int index = 0; index < allocations.size(); index++) {
                MesApsBatchAllocationEntity allocation = allocations.get(index);
                BigDecimal target = index == allocations.size() - 1
                        ? newTotal.subtract(assignedTarget)
                        : newTotal.multiply(allocation.getAllocatedQuantity())
                        .divide(totalAllocated, 8, RoundingMode.HALF_UP)
                        .min(allocation.getAllocatedQuantity());
                BigDecimal old = Objects.requireNonNullElse(allocation.getInStockQuantity(), BigDecimal.ZERO);
                BigDecimal delta = target.subtract(old);
                if (delta.signum() < 0) {
                    throw new CheckedException("APS批次来源累计入库分摊发生倒退，拒绝回传");
                }
                assignedTarget = assignedTarget.add(target);
                allocation.setInStockQuantity(target);
                if (apsBatchAllocationMapper.updateById(allocation) != 1) {
                    throw new CheckedException("APS来源入库数量保存失败，请保留原入库请求号核对");
                }
                if (delta.signum() > 0) {
                    callbacks.add(lifeCycleCallback(task, allocation.getIssueNo(), allocation.getIssueId(),
                            "IN_WMS:" + mesTaskInWmsDTO.getRequestId(), null,
                            OrderLongStateEnum.WAREHOUSE.getCode(), delta, null,
                            String.format("APS批次【%s】MES工单【%s】本次向来源计划分摊入库【%s】，批次本次入库【%s】，仓库【%s/%s】，操作时间【%s】",
                                    task.getApsBatchNo(), task.getCode(), delta, mesTaskInWmsDTO.getInWmsNum(),
                                    mesTaskInWmsDTO.getWarehouseCode(), mesTaskInWmsDTO.getWarehouseName(), LocalDate.now())));
                }
            }
        } else {
            callbacks.add(lifeCycleCallback(task, task.getPlanNo(), null,
                    "IN_WMS:" + StrUtil.blankToDefault(mesTaskInWmsDTO.getRequestId(), UUID.randomUUID().toString()),
                    null, OrderLongStateEnum.WAREHOUSE.getCode(), mesTaskInWmsDTO.getInWmsNum(), null,
                    String.format("工单编号【%s】,入库数量【%s】,仓库编码【%s】,仓库名称【%s】,操作时间【%s】",
                            task.getCode(), mesTaskInWmsDTO.getInWmsNum(), mesTaskInWmsDTO.getWarehouseCode(),
                            mesTaskInWmsDTO.getWarehouseName(), LocalDate.now())));
        }
        if (!callbacks.isEmpty()) {
            R<Boolean> response = remotePlnIssueService.edit(callbacks);
            if (response == null || response.getCode() != 0 || !Boolean.TRUE.equals(response.getData())) {
                throw new CheckedException(String.format("%s的工单未取得计划模块成功回执，请保留原入库请求号核对", task.getCode()));
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void feedbackPlanCompletion(MesTaskEntity task, BigDecimal completeNum) {
        if (task == null || StrUtil.isBlank(task.getPlanNo())) return;
        planCompletionOutbox.enqueue(task, completeNum);
    }

    private List<MesApsBatchAllocationEntity> batchAllocations(MesTaskEntity task, boolean lock) {
        if (task == null || StrUtil.isBlank(task.getApsBatchNo())) return Collections.emptyList();
        if (lock) baseMapper.acquireApsBatchTransactionLock(task.getApsBatchNo());
        List<MesApsBatchAllocationEntity> result = apsBatchAllocationMapper.selectList(
                new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                        .eq(MesApsBatchAllocationEntity::getMesTaskId, task.getId())
                        .orderByAsc(MesApsBatchAllocationEntity::getAllocationSeq));
        if (result.isEmpty()) throw new CheckedException("APS批次工单缺少来源分配账：" + task.getApsBatchNo());
        BigDecimal total = result.stream().map(MesApsBatchAllocationEntity::getAllocatedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (task.getNoticeNum() == null || total.compareTo(task.getNoticeNum()) != 0) {
            throw new CheckedException("APS批次工单数量与来源分配账不守恒：" + task.getApsBatchNo());
        }
        return result;
    }

    private OmsOrderLifeCycleDTO lifeCycleCallback(MesTaskEntity task, String issueNo, Long issueId,
                                                   String event, String issueStatus, String lifeCycleStatus,
                                                   BigDecimal finishQuantity, LocalDate finishDate, String content) {
        OmsOrderLifeCycleDTO callback = new OmsOrderLifeCycleDTO();
        callback.setRequestId("MES:" + StrUtil.blankToDefault(task.getApsBatchNo(), task.getCode()) + ":"
                + event + ":" + Objects.requireNonNullElse(issueId, issueNo));
        callback.setNo(issueNo);
        callback.setProductCode(task.getMatCode());
        callback.setIssueStatus(issueStatus);
        callback.setStatus(lifeCycleStatus);
        callback.setType("mes");
        callback.setFinishNum(finishQuantity);
        callback.setFinishTime(finishDate);
        callback.setContent(content);
        return callback;
    }

    /**
     * 原号核账不重取今日BOM/质量/库存，不触发WMS或PLAN重投。
     * 独立RC事务避免调用者旧快照漏掉已经提交的回执；缺回执保持UNKNOWN。
     */
    @Override
    @org.springframework.security.access.prepost.PreAuthorize("@pms.hasPermission('mesTask:update')")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, readOnly = true)
    public MesTaskInWmsReceiptDTO resolveInWms(MesTaskInWmsDTO command) {
        String factory = factoryAccess.require("write");
        Long tenant = com.kitex.common.data.tenant.TenantContextHolder.getTenantId();
        if (tenant == null || tenant <= 0 || command == null || command.getId() == null
                || command.getId() <= 0 || StrUtil.isBlank(command.getRequestId())
                || command.getRequestId().trim().length() > 64 || StrUtil.isBlank(command.getWarehouseCode())
                || command.getInWmsNum() == null || command.getInWmsNum().signum() <= 0) {
            throw new CheckedException("核账须原请求号、原工单、原仓库和原入库数量");
        }
        String requestId = command.getRequestId().trim();
        MesTaskInWmsCommandEntity original = isInWmsV2(command) ? taskInWmsCommands.original(command) : null;
        MesTaskInWmsReceiptEntity receipt = taskInWmsReceiptMapper.permanentRequest(tenant, requestId);
        if (receipt != null) {
            assertInWmsReceipt(receipt, command, factory, inWmsFingerprint(command));
        }
        boolean cancelled = original != null && "CANCELLED_BEFORE_DISPATCH".equals(original.getStatus());
        if (receipt != null && (cancelled || (isInWmsV2(command)
                && (original == null || !"OUTBOUND_STARTED".equals(original.getStatus()))))) {
            throw new CheckedException("入库回执与永久发送边界冲突，请人工核对原号");
        }
        String state = receipt != null ? "RECORDED" : cancelled ? "CANCELLED_BEFORE_DISPATCH" : "UNKNOWN";
        return new MesTaskInWmsReceiptDTO(state, requestId, factory,
                command.getId().toString(), command.getWarehouseCode(),
                command.getInWmsNum().stripTrailingZeros().toPlainString(),
                receipt == null ? null : receipt.getId().toString(),
                receipt == null ? null : StrUtil.blankToDefault(receipt.getReversalStatus(), "LEGACY_UNSPECIFIED"),
                original == null ? null : original.getId().toString(), original == null ? null : original.getStatus());
    }

    /**
     * 保留新协议命名域，即使遗漏版本也不能回退旧发送路径。
     */
    private boolean isInWmsV2(MesTaskInWmsDTO command) {
        return command.getCommandVersion() != null || StrUtil.startWith(StrUtil.trim(command.getRequestId()), "IW2:");
    }

    /**
     * 旧V1指纹算法保持不变；另逐项核真实库存身份，不能只依赖拼接指纹或工厂过滤。
     */
    private void assertInWmsReceipt(MesTaskInWmsReceiptEntity receipt, MesTaskInWmsDTO command,
                                    String factory, String fingerprint) {
        if (!Objects.equals(receipt.getFactoryCode(), factory)
                || !Objects.equals(receipt.getMesTaskId(), command.getId())
                || !Objects.equals(receipt.getWarehouseCode(), command.getWarehouseCode())
                || !Objects.equals(receipt.getRequestFingerprint(), fingerprint)
                || receipt.getInStockQuantity() == null
                || receipt.getInStockQuantity().compareTo(command.getInWmsNum()) != 0) {
            throw new CheckedException("原入库号的工厂、工单、仓库或完整参数不一致，禁止改参重投");
        }
    }

    /**
     * 当前WMS仍为Double接口，预览和实际发送均禁止静默丢失精确数量。
     */
    private double exactWmsInboundQuantity(BigDecimal quantity) {
        double value = quantity.doubleValue();
        if (!Double.isFinite(value) || BigDecimal.valueOf(value).compareTo(quantity) != 0) {
            throw new CheckedException("本次精确入库数量无法由当前WMS数量接口无损接收，未发送；请先完成数量接口升级");
        }
        return value;
    }

    private String inWmsFingerprint(MesTaskInWmsDTO command) {
        try {
            String raw = String.join("|", String.valueOf(command.getId()), String.valueOf(command.getWarehouseCode()),
                    String.valueOf(command.getWarehouseName()), String.valueOf(command.getInWmsNum()),
                    String.valueOf(command.getRemark()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("入库命令指纹生成失败");
        }
    }

    private InspectionProcedureCreateParam buildCreateParam(MesProduceTaskEntity task,
                                                            QmsQualityStandardEntity standard,
                                                            String sourceRequestId) {
        InspectionProcedureCreateParam result = new InspectionProcedureCreateParam();
        result.setTaskCode(task.getCode());
        result.setSourceTaskCode(task.getCode());
        result.setSourceRequestId(sourceRequestId);
        result.setMatCode(task.getMatCode());
        result.setMatName(task.getMatName());
        result.setMatSpec(task.getMatSpec());
        result.setProcessCode(task.getRouteCode());
        result.setProcessName(task.getRouteName());
        result.setLabelType(ProcedureLabelTypeEnum.FIRST.name());
        result.setStandardId(standard.getId());
        result.setNum(Math.max(1, task.getNoticeNum().intValue()));
        result.setDeptCode(task.getWorkshopCode());
        result.setOrderNo(task.getTaskCode());
        if (InspectionMethodEnum.PENDING.name().equals(standard.getInspectionMethod())) {
            String configured = remoteParamService.getByKey("QMS_DEFAULT_NUM").getData();
            if (StrUtil.isBlank(configured)) {
                throw new CheckedException("未配置QMS_DEFAULT_NUM抽检数量");
            }
            result.setInspectionNum(Integer.parseInt(configured));
        }
        return result;
    }

    private boolean containsInspectionType(MesInspectionRuleEntity rule, ProcedureLabelTypeEnum type) {
        if (rule == null || StrUtil.isBlank(rule.getInspectionTypes())) {
            return false;
        }
        return Arrays.stream(rule.getInspectionTypes().split(","))
                .map(String::trim).anyMatch(value -> matchesInspectionType(value, type));
    }

    /** 兼容按枚举码和按中文阶段名称存储的质检规则。 */
    private boolean matchesInspectionType(String value, ProcedureLabelTypeEnum type) {
        return type.name().equalsIgnoreCase(value) || type.getName().equals(value);
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
        return message.length() > 800 ? message.substring(0, 800) : message;
    }

    /**
     * 根据产品编码和工位编码查询当前用户需要做的工单信息
     * 如果有已经开始的则查询开始的，没有开始的则查询最近需要开始的工单信息
     *
     * @param queryDTO 查询参数
     * @return 当前用户需要做的工单信息
     */
    @Override
    public MesTaskCurrentWorkVO getCurrentWorkByProductAndWorksite(MesTaskQueryByProductWorksiteDTO queryDTO) {
        // 获取当前用户信息
        MomUser currentUser = SecurityUtils.getUser();
        if (currentUser == null) {
            throw new CheckedException("用户未登录");
        }

        // 构建查询条件
        LambdaQueryWrapper<MesTaskArrangeEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 根据产品编码查询
        queryWrapper.eq(MesTaskArrangeEntity::getMatCode, queryDTO.getProductCode());

        // 根据工位编码查询（工位编码是逗号分隔的字符串，需要模糊匹配）
        queryWrapper.apply("{0} = ANY(string_to_array(worksite_codes, ','))", queryDTO.getWorksiteCode());

        // 根据车间编码查询（如果提供）
        if (StrUtil.isNotBlank(queryDTO.getWorkshopCode())) {
            queryWrapper.eq(MesTaskArrangeEntity::getWorkshopCode, queryDTO.getWorkshopCode());
        }

        // 根据班次编码查询（如果提供）
        if (StrUtil.isNotBlank(queryDTO.getShiftCode())) {
            queryWrapper.eq(MesTaskArrangeEntity::getShiftCode, queryDTO.getShiftCode());
        }

        // 查询当前用户的派工任务
        queryWrapper.apply("{0} = ANY(string_to_array(worker_code, ','))", currentUser.getUsername());

        // 查询状态为已接收、生产中、暂停中的任务（已开始的任务）
        List<String> startedStatusList = Arrays.asList(
                MesProduceTaskStatusEnum.RECEIVE.name(),
                MesProduceTaskStatusEnum.PRODUCT.name(),
                MesProduceTaskStatusEnum.PAUSE.name()
        );

        // 查询状态为待接收的任务（未开始的任务）
        List<String> notStartedStatusList = Arrays.asList(
                MesProduceTaskStatusEnum.UN_RECEIVE.name()
        );

        // 先查询已开始的任务
        queryWrapper.in(MesTaskArrangeEntity::getStatus, startedStatusList);
        queryWrapper.orderByDesc(MesTaskArrangeEntity::getStartTime);
        queryWrapper.last("LIMIT 1");

        MesTaskArrangeEntity startedTask = mesTaskArrangeMapper.selectOne(queryWrapper);

        if (startedTask != null) {
            // 如果找到已开始的任务，返回该任务
            return convertToCurrentWorkVO(startedTask, true);
        }

        // 如果没有已开始的任务，查询最近需要开始的任务
        queryWrapper.clear();
        queryWrapper.eq(MesTaskArrangeEntity::getMatCode, queryDTO.getProductCode());
        queryWrapper.apply("{0} = ANY(string_to_array(worksite_codes, ','))", queryDTO.getWorksiteCode());

        if (StrUtil.isNotBlank(queryDTO.getWorkshopCode())) {
            queryWrapper.eq(MesTaskArrangeEntity::getWorkshopCode, queryDTO.getWorkshopCode());
        }

        if (StrUtil.isNotBlank(queryDTO.getShiftCode())) {
            queryWrapper.eq(MesTaskArrangeEntity::getShiftCode, queryDTO.getShiftCode());
        }

        queryWrapper.apply("{0} = ANY(string_to_array(worker_code, ','))", currentUser.getUsername());
        queryWrapper.in(MesTaskArrangeEntity::getStatus, notStartedStatusList);
        queryWrapper.orderByAsc(MesTaskArrangeEntity::getCreateTime);
        queryWrapper.last("LIMIT 1");

        MesTaskArrangeEntity notStartedTask = mesTaskArrangeMapper.selectOne(queryWrapper);

        if (notStartedTask != null) {
            // 返回未开始的任务
            return convertToCurrentWorkVO(notStartedTask, false);
        }

        // 如果没有找到任何任务，返回null
        return null;
    }

	/**
	 * {@inheritDoc}
	 * 先通过实际组织读取守卫核对唯一原单，再复制准确身份和业务事实，不从客户端补齐归属。
	 */
	@Override
	public MesTaskDTO getByTaskCode(String taskCode) {
		MesTaskEntity task = taskCommands.readByCode(taskCode);
		MesTaskDTO result = new MesTaskDTO();
		BeanUtil.copyProperties(task, result);
		return result;
	}

    /**
     * 将MesTaskArrange转换为MesTaskCurrentWorkVO
     *
     * @param arrange   派工任务
     * @param isStarted 是否已开始
     * @return 当前工作VO
     */
    private MesTaskCurrentWorkVO convertToCurrentWorkVO(MesTaskArrangeEntity arrange, boolean isStarted) {
        MesTaskCurrentWorkVO vo = new MesTaskCurrentWorkVO();

        vo.setArrangeId(arrange.getId());
        vo.setArrangeCode(arrange.getCode());
        vo.setTaskCode(arrange.getTaskCode());
        vo.setProduceTaskCode(arrange.getProduceTaskCode());
        vo.setProductCode(arrange.getMatCode());
        vo.setProductName(arrange.getMatName());
        vo.setProductSpec(arrange.getMatSpec());
        vo.setRouteCode(arrange.getRouteCode());
        vo.setRouteName(arrange.getRouteName());
        vo.setRouteType(arrange.getRouteType());
        vo.setExecutionContract(executionTemplateService.contract(arrange));

        // 从工位编码中提取当前工位
        String[] worksiteCodes = arrange.getWorksiteCodes().split(",");
        String[] worksiteNames = arrange.getWorksiteNames().split(",");
        // 这里简化处理，取第一个工位，实际可能需要根据具体业务逻辑调整
        vo.setWorksiteCode(worksiteCodes.length > 0 ? worksiteCodes[0] : arrange.getWorksiteCodes());
        vo.setWorksiteName(worksiteNames.length > 0 ? worksiteNames[0] : arrange.getWorksiteNames());

        vo.setNoticeNum(arrange.getNoticeNum());
        vo.setCompleteNum(arrange.getCompleteNum());
        vo.setUnPassNum(arrange.getUnPassNum());
        vo.setStatus(arrange.getStatus());
        vo.setStartTime(arrange.getStartTime());
        vo.setWorkshopCode(arrange.getWorkshopCode());
        vo.setWorkshopName(arrange.getWorkshopName());
        vo.setShiftCode(arrange.getShiftCode());
        vo.setShiftName(arrange.getShiftName());
        vo.setBatchNo(arrange.getBatchNo());
        vo.setWorksiteType(arrange.getWorksiteTypes());
        vo.setWrapRuleCode(arrange.getWrapRuleCode());
        vo.setWrapCapacity(arrange.getWrapCapacity());
        vo.setIsStarted(isStarted);

        // 设置优先级：已开始的任务优先级更高（数字更小）
        vo.setPriority(isStarted ? 1 : 2);

        return vo;
    }

    private record ReworkRouteSelection(MdmProcessRouteWorkingDTO route,
                                        MesProduceTaskEntity sourceProduceTask,
                                        String sourceProduceTaskCode,
                                        String routeNodeCode) {
    }

    private record InboundReportAllocation(MesTaskReportEntity report, BigDecimal quantity) {
    }

    private record QualityReceiptEvidence(String taskIdsJson, String fingerprint, String snapshotJson) {
    }
}
