package com.kitex.mom.mes.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateUtil;
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
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteBomService;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.MesTaskStatusEnum;
import com.kitex.mom.mes.enums.MesSourceQuantityKind;
import com.kitex.mom.mes.enums.QualityGateStatusEnum;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesApsBatchAllocationMapper;
import com.kitex.mom.mes.mapper.MesApsSourceQuantityEventMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.mapper.MesTaskInWmsReceiptMapper;
import com.kitex.mom.mes.mapper.MesPlanWithdrawalReceiptMapper;
import com.kitex.mom.mes.mapper.MesWarehousePickingBillMapper;
import com.kitex.mom.mes.mapper.MesWarehouseFeedMapper;
import com.kitex.mom.mes.mapper.MesToolingExecutionMapper;
import com.kitex.mom.mes.mapper.MesTaskExecutionSegmentMapper;
import com.kitex.mom.mes.service.MesInspectionGenRecordService;
import com.kitex.mom.mes.service.MesInspectionRuleService;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.service.MesQualityQuantityService;
import com.kitex.mom.mes.service.MesApsSourceQuantityService;
import com.kitex.mom.mes.service.MesScrapMaterialService;
import com.kitex.mom.mes.vo.MesTaskReportOmsVO;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.oms.enums.OrderLongStateEnum;
import com.kitex.mom.plan.feign.RemotePlnIssueService;
import com.kitex.mom.qms.api.feign.RemoteProcedureService;
import com.kitex.mom.qms.api.feign.RemoteQmsReworkService;
import com.kitex.mom.qms.api.feign.RemoteQualityStandardService;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;
import com.kitex.mom.qms.dto.QmsProcedureSourceWithdrawalDTO;
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
 * 工单获取
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
    private final RemoteQualityStandardService remoteQualityStandardService;
    private final RemoteParamService remoteParamService;
    private final RemoteClassesService remoteClassesService;
    private final MesInspectionGenRecordService genRecordService;
    private final MesTaskReportMapper mesTaskReportMapper;
    private final RemoteProductInNoticeService inNoticeService;
    private final RemoteMaterialService matService;
    private final MesTaskArrangeMapper mesTaskArrangeMapper;
    private final RemoteQmsReworkService remoteQmsReworkService;
    private final MesReworkMaterialPlanService reworkMaterialPlans;
    private final MesQualityQuantityService qualityQuantityService;
    private final MesApsBatchAllocationMapper apsBatchAllocationMapper;
    private final MesApsSourceQuantityEventMapper apsSourceQuantityEventMapper;
    private final MesTaskInWmsReceiptMapper taskInWmsReceiptMapper;
    private final MesPlanWithdrawalReceiptMapper planWithdrawalReceiptMapper;
    private final MesWarehousePickingBillMapper warehousePickingBillMapper;
    private final MesWarehouseFeedMapper warehouseFeedMapper;
    private final MesApsSourceQuantityService sourceQuantityService;
    private final MesScrapMaterialService scrapMaterialService;
    private final MesToolingExecutionMapper toolingExecutionMapper;
    private final MesTaskExecutionSegmentMapper taskExecutionSegmentMapper;

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
        String requestFingerprint = null;
        if (!Boolean.TRUE.equals(command.getPreview())) {
            requestFingerprint = sha256(String.join("|", String.valueOf(command.getIssueId()),
                    command.getIssueNo().trim(), command.getExpectedQuantity().stripTrailingZeros().toPlainString(),
                    command.getFactoryCode().trim(), command.getExpectedEvidenceFingerprint().trim(),
                    command.getReason().trim()));
            planWithdrawalReceiptMapper.lockKey("REQUEST:" + requestId);
            MesPlanWithdrawalReceiptEntity replay = planWithdrawalReceiptMapper.selectOne(
                    new LambdaQueryWrapper<MesPlanWithdrawalReceiptEntity>()
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
        List<MesTaskEntity> tasks = taskIds.isEmpty() ? List.of() : list(new LambdaQueryWrapper<MesTaskEntity>()
                .in(MesTaskEntity::getId, taskIds).orderByAsc(MesTaskEntity::getId).last("FOR UPDATE"));
        if (tasks.size() != taskIds.size()) throw new CheckedException("MES来源分配账关联工单缺失，禁止撤回");
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
                        + "；仅新建、已下达或已派工但尚未接收的工单允许计划级撤回");
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
            if (!Set.of(MesProduceTaskStatusEnum.WAIT.name(), MesProduceTaskStatusEnum.UN_RECEIVE.name())
                    .contains(produceTask.getStatus()) || produceTask.getStartTime() != null || produceTask.getFinishTime() != null
                    || positive(produceTask.getCompleteNum()) || positive(produceTask.getQualityReleasedNum())
                    || positive(produceTask.getUnPassNum())) {
                throw new CheckedException("MES工序任务[" + produceTask.getCode()
                        + "]已被接收、开工、完工或形成质量数量，必须进入现场停止/冲销闭环");
            }
        }
        Set<String> produceCodeSet = new HashSet<>(produceTaskCodes);
        for (MesTaskArrangeEntity arrange : arranges) {
            if (!produceCodeSet.contains(arrange.getProduceTaskCode())) {
                throw new CheckedException("MES派工任务[" + arrange.getCode() + "]没有精确工序任务来源");
            }
            if (!MesProduceTaskStatusEnum.UN_RECEIVE.name().equals(arrange.getStatus())
                    || arrange.getStartTime() != null || arrange.getFinishTime() != null
                    || positive(arrange.getCompleteNum()) || positive(arrange.getQualityReleasedNum())
                    || positive(arrange.getUnPassNum()) || StrUtil.isNotBlank(arrange.getActualWorksiteCode())) {
                throw new CheckedException("MES派工任务[" + arrange.getCode()
                        + "]已接收、开工、完工或形成质量数量，必须进入现场停止/冲销闭环");
            }
        }
        long executionSegmentCount = taskCodes.isEmpty() ? 0 : taskExecutionSegmentMapper.selectCount(
                new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                        .in(MesTaskExecutionSegmentEntity::getTaskCode, taskCodes));
        if (executionSegmentCount > 0) throw new CheckedException("MES工单已有现场执行时间段，不能按开工前计划撤回");
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
                toolingExecutions, qmsEvidence);
        if (Boolean.TRUE.equals(command.getPreview())) {
            return new MesPlanWithdrawalDTO.Receipt().setRequestId(requestId).setIssueId(command.getIssueId())
                    .setIssueNo(command.getIssueNo().trim()).setStatus("ELIGIBLE")
                    .setClosedTaskCount(0).setClosedTaskCodes(List.of()).setClosedProduceTaskCount(0)
                    .setClosedArrangeCount(0).setCancelledQmsTaskCount(0).setQmsEvidenceFingerprint(qmsEvidence)
                    .setEvidenceFingerprint(actualEvidence).setHandledTime(LocalDateTime.now())
                    .setMessage("MES工单及其工序、待接收派工、未开始质检均无执行事实，允许提交撤回审批");
        }
        if (!Objects.equals(actualEvidence, command.getExpectedEvidenceFingerprint().trim())) {
            throw new CheckedException("MES计划撤回证据已变化，请刷新补产需求后重新申请");
        }
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
                        + arranges.size() + "张待接收派工，并作废" + cancelledQmsTaskCount + "张QMS质检任务")
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
                                      List<MesToolingExecutionEntity> toolingExecutions, String qmsEvidence) {
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
        return sha256(taskFacts + '|' + allocations + '|' + produceFacts + '|' + arrangeFacts + '|'
                + qmsGenerationFacts + '|' + toolingFacts + '|' + qmsEvidence);
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
                        (left, right) -> { throw new CheckedException("MES来源分配账存在重复PLAN计划"); }, LinkedHashMap::new));
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
     * 查询分页
     *
     * @param pageParam
     * @param query
     * @return
     */
    @Override
    public IPage<MesTaskEntity> queryPage(Page<MesTaskEntity> pageParam, MesTaskEntity query) {
        QueryWrapper<MesTaskEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesTaskEntity::getCreateTime);
        IPage<MesTaskEntity> page = this.page(pageParam, autoQueryWrapper);
        // 工单的任务id
        List<String> collect = page.getRecords().stream().map(MesTaskEntity::getCode).collect(Collectors.toList());
        if (CollUtil.isEmpty(collect)) {
            return page;
        }
        List<MesProduceTaskEntity> list = produceTaskService.list(Wrappers.<MesProduceTaskEntity>lambdaQuery().in(MesProduceTaskEntity::getTaskCode, collect));
        page.getRecords().forEach(item -> {
            List<RouteProcessDTO> processDTOS = new ArrayList<>();
            //获取对应的工序任务进度
            List<MesProduceTaskEntity> mesProduceTask = list.stream().filter(task -> task.getTaskCode().equals(item.getCode())).toList();
            if (CollUtil.isNotEmpty(mesProduceTask)) {
                mesProduceTask.forEach(task -> {
                    RouteProcessDTO process = new RouteProcessDTO();
                    process.setProcessName(task.getRouteName());
                    process.setFinishNum(task.getCompleteNum() != null ? task.getCompleteNum() : BigDecimal.ZERO);
                    if (task.getNoticeNum() != null && task.getNoticeNum().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal percent = task.getCompleteNum() != null ?
                                task.getCompleteNum().multiply(new BigDecimal("100")).divide(task.getNoticeNum(), 2, RoundingMode.HALF_UP) :
                                BigDecimal.ZERO;
                        process.setPercent(percent);
                    } else {
                        process.setPercent(BigDecimal.ZERO);
                    }
                    processDTOS.add(process);
                });
                item.setProcessList(processDTOS);
            }
        });

        return page;
    }

    /**
     * 根据生产计划生成生产工单
     *
     * @param receive
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(MesTaskReceiveDTO receive) {
        if (receive == null) {
            throw new CheckedException("生产计划接收参数不能为空");
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
                return;
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
        MesTaskEntity existing = this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getReworkRequestId, requestId), false);
        if (existing != null) {
            if (!Objects.equals(existing.getSourceInspectionType(), receive.getSourceInspectionType())
                    || !Objects.equals(existing.getSourceInspectionTaskId(), receive.getSourceInspectionTaskId())
                    || !Objects.equals(existing.getSourceHandleRecordId(), receive.getSourceHandleRecordId())) {
                throw new CheckedException("返工请求号已被其他质量处置使用");
            }
            return existing.getCode();
        }
        if (StrUtil.isBlank(receive.getSourceTaskCode()) || receive.getNoticeNum() == null
                || receive.getNoticeNum().signum() <= 0) {
            throw new CheckedException("返工必须提供原生产工单和大于0的返工数量");
        }
        if (!Set.of("PROCEDURE", "PRODUCT").contains(receive.getSourceInspectionType().toUpperCase(Locale.ROOT))) {
            throw new CheckedException("当前仅允许过程检或成品检处置生成MES返工工单");
        }
        MesTaskEntity source = this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, receive.getSourceTaskCode().trim()));
        if (source == null) {
            throw new CheckedException("未找到质量处置对应的原生产工单：" + receive.getSourceTaskCode());
        }
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
        String requestedProcess = StrUtil.trim(receive.getReworkProcessCode());
        MdmProcessRouteWorkingDTO reworkRoute = sourceRoute.stream()
                .filter(item -> StrUtil.isNotBlank(requestedProcess)
                        && requestedProcess.equals(StrUtil.blankToDefault(item.getProcessCode(), item.getRouteCode())))
                .findFirst()
                .orElseGet(() -> sourceRoute.stream()
                        .filter(item -> item.getNextRouteCode() == null || item.getNextRouteCode().length == 0)
                        .findFirst().orElse(null));
        if (reworkRoute == null || StrUtil.isNotBlank(requestedProcess)
                && !requestedProcess.equals(StrUtil.blankToDefault(reworkRoute.getProcessCode(), reworkRoute.getRouteCode()))) {
            throw new CheckedException("指定返工工序不在原工单冻结路线中：" + requestedProcess);
        }
        String processCode = StrUtil.blankToDefault(reworkRoute.getProcessCode(), reworkRoute.getRouteCode());
        reworkRoute.setPreRouteCode(new String[0]);
        reworkRoute.setNextRouteCode(new String[0]);
        reworkRoute.setOrderNum(1);
        reworkRoute.setRouteOrder(1);
        // 返工完成后由返工协调单生成独立复检，避免普通首/中/末检规则提前把返工工序锁死。
        reworkRoute.setIsInspection("0");

        MesTaskEntity rework = new MesTaskEntity();
        BeanUtil.copyProperties(source, rework, "id", "code", "status", "planNo", "materialReservationSnapshot",
                "apsOperationSnapshot", "createBy", "createTime", "updateBy", "updateTime");
        rework.setCode(remoteSeqConfigService.create("mes_task").getData());
        if (StrUtil.isBlank(rework.getCode())) {
            throw new CheckedException("返工工单编码生成失败");
        }
        rework.setResourceType("REWORK");
        rework.setReworkMaterialPlanId(null);
        rework.setBomSnapshot(reworkMaterialPlans.originalBom(source));
        rework.setResourceId(String.valueOf(receive.getSourceInspectionTaskId()));
        rework.setReworkRequestId(requestId);
        rework.setSourceTaskCode(source.getCode());
        rework.setSourceInspectionType(receive.getSourceInspectionType().toUpperCase(Locale.ROOT));
        rework.setSourceInspectionTaskId(receive.getSourceInspectionTaskId());
        rework.setSourceHandleRecordId(receive.getSourceHandleRecordId());
        rework.setReworkProcessCode(processCode);
        rework.setReworkReason(StrUtil.trim(receive.getReworkReason()));
        rework.setReworkCallbackStatus("PENDING");
        rework.setReworkCallbackCount(0);
        rework.setReinspectionTaskId(null);
        rework.setReinspectionStatus("WAIT");
        rework.setReinspectionMessage("返工尚未完工");
        rework.setReworkQualifiedNum(BigDecimal.ZERO);
        rework.setReworkRejectedNum(BigDecimal.ZERO);
        rework.setProcessRouteSnapshot(JSONUtil.toJsonStr(List.of(reworkRoute)));
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
        this.save(rework);
        if (source.getPlanNo() != null || source.getApsBatchNo() != null) {
            sourceQuantityService.record(source, "REWORK-CREATED:" + requestId,
                    MesSourceQuantityKind.REWORK_CREATED, receive.getNoticeNum(), requestId,
                    "质量处置创建返工工单【" + rework.getCode() + "】，返工数量【" + receive.getNoticeNum()
                            + "】，原工单【" + source.getCode() + "】");
        }
        return rework.getCode();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long notifyReworkCompletion(Long taskId, String produceTaskCode, BigDecimal completedQuantity) {
        MesTaskEntity task = this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getId, taskId).last("FOR UPDATE"));
        if (task == null || !"REWORK".equals(task.getResourceType())) {
            return null;
        }
        if ("SUCCEEDED".equals(task.getReworkCallbackStatus()) && task.getReinspectionTaskId() != null) {
            return task.getReinspectionTaskId();
        }
        MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getCode, produceTaskCode));
        if (produceTask == null || !Boolean.TRUE.equals(produceTask.getLastRoute())) {
            throw new CheckedException("返工完工回传必须关联最终返工工序");
        }
        int callbackCount = Objects.requireNonNullElse(task.getReworkCallbackCount(), 0) + 1;
        MesTaskEntity sourceTask = this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, task.getSourceTaskCode()));
        if (sourceTask == null) throw new CheckedException("返工工单缺少可归集的原生产工单");
        if (sourceTask.getPlanNo() != null || sourceTask.getApsBatchNo() != null) {
            sourceQuantityService.record(sourceTask, "REWORK-COMPLETED:" + task.getReworkRequestId(),
                    MesSourceQuantityKind.REWORK_COMPLETED, completedQuantity, task.getCode(),
                    "返工工单【" + task.getCode() + "】完工，数量【" + completedQuantity + "】");
        }
        try {
            QmsReworkCompletionDTO command = new QmsReworkCompletionDTO();
            command.setRequestId(task.getReworkRequestId());
            command.setMesTaskCode(task.getCode());
            command.setMesProduceTaskCode(produceTask.getCode());
            command.setCompletedQuantity(completedQuantity);
            command.setProcessCode(produceTask.getRouteCode());
            command.setProcessName(produceTask.getRouteName());
            R<Long> response = remoteQmsReworkService.mesComplete(command);
            if (response == null || response.getData() == null) {
                throw new IllegalStateException(response == null ? "QMS未返回复检任务" : response.getMsg());
            }
            this.update(new LambdaUpdateWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getId, task.getId())
                    .set(MesTaskEntity::getReworkCallbackStatus, "SUCCEEDED")
                    .set(MesTaskEntity::getReworkCallbackMessage, "已创建返工复检任务")
                    .set(MesTaskEntity::getReworkCallbackCount, callbackCount)
                    .set(MesTaskEntity::getReinspectionTaskId, response.getData())
                    .set(MesTaskEntity::getReinspectionStatus, "WAIT")
                    .set(MesTaskEntity::getReinspectionMessage, "等待QMS返工复检结论"));
            return response.getData();
        } catch (Exception exception) {
            this.update(new LambdaUpdateWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getId, task.getId())
                    .set(MesTaskEntity::getReworkCallbackStatus, "FAILED")
                    .set(MesTaskEntity::getReworkCallbackMessage,
                            StrUtil.maxLength(rootMessage(exception), 1000))
                    .set(MesTaskEntity::getReworkCallbackCount, callbackCount));
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long retryReworkCallback(Long taskId) {
        MesTaskEntity task = this.getById(taskId);
        if (task == null || !"REWORK".equals(task.getResourceType())) {
            throw new CheckedException("返工工单不存在");
        }
        if (!MesTaskStatusEnum.FINISH.name().equals(task.getStatus())) {
            throw new CheckedException("返工工单尚未完工，不能创建复检任务");
        }
        MesProduceTaskEntity finalTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTaskCode, task.getCode())
                .eq(MesProduceTaskEntity::getLastRoute, Boolean.TRUE));
        if (finalTask == null) {
            throw new CheckedException("返工工单缺少最终工序");
        }
        return notifyReworkCompletion(taskId, finalTask.getCode(), task.getCompleteNum());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncReworkInspection(MesReworkInspectionResultDTO result) {
        if (result == null || StrUtil.isBlank(result.getRequestId()) || result.getQmsTaskId() == null) {
            throw new CheckedException("返工复检同步缺少返工请求号或QMS任务ID");
        }
        MesTaskEntity task = this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getReworkRequestId, result.getRequestId().trim()).last("FOR UPDATE"), false);
        if (task == null || !"REWORK".equals(task.getResourceType())) {
            throw new CheckedException("未找到匹配的MES返工工单");
        }
        if (!Objects.equals(task.getReinspectionTaskId(), result.getQmsTaskId())) {
            throw new CheckedException("复检任务与MES返工工单登记不一致");
        }
        MesProduceTaskEntity finalTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTaskCode, task.getCode())
                .eq(MesProduceTaskEntity::getLastRoute, Boolean.TRUE));
        if (finalTask == null) {
            throw new CheckedException("返工工单缺少最终工序，不能同步复检数量");
        }
        BigDecimal total = BigDecimal.valueOf(Objects.requireNonNullElse(result.getTotalNum(), 0));
        BigDecimal usable = BigDecimal.valueOf(Objects.requireNonNullElse(result.getUsableNum(), 0));
        BigDecimal handled = BigDecimal.valueOf(Objects.requireNonNullElse(result.getHandleNum(), 0));
        BigDecimal completed = Objects.requireNonNullElse(finalTask.getCompleteNum(), BigDecimal.ZERO);
        if (total.signum() <= 0 || total.compareTo(completed) > 0 || handled.compareTo(total) > 0) {
            throw new CheckedException("返工复检总数、已处置数或MES完工数关系不正确");
        }
        boolean qualified = "QUALIFIED".equals(result.getResult());
        if (qualified) {
            usable = total;
            handled = total;
        }
        MesQualityGateDecision quantity = qualityQuantityService.reconcileRework(
                finalTask.getCode(), usable, handled, qualified);
        BigDecimal newQualified = Objects.requireNonNullElse(quantity.getReleasedQuantity(), BigDecimal.ZERO);
        BigDecimal newRejected = Objects.requireNonNullElse(quantity.getRejectedQuantity(), BigDecimal.ZERO);
        BigDecimal oldQualified = Objects.requireNonNullElse(task.getReworkQualifiedNum(), BigDecimal.ZERO);
        BigDecimal oldRejected = Objects.requireNonNullElse(task.getReworkRejectedNum(), BigDecimal.ZERO);
        if (newQualified.compareTo(oldQualified) < 0 || newRejected.compareTo(oldRejected) < 0) {
            throw new CheckedException("返工复检累计数量发生倒退，拒绝同步");
        }
        MesTaskEntity sourceTask = this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
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
        this.update(new LambdaUpdateWrapper<MesTaskEntity>().eq(MesTaskEntity::getId, task.getId())
                .set(MesTaskEntity::getReworkQualifiedNum, newQualified)
                .set(MesTaskEntity::getReworkRejectedNum, newRejected)
                .set(MesTaskEntity::getReinspectionStatus, status)
                .set(MesTaskEntity::getReinspectionMessage, "复检任务" + result.getQmsTaskId()
                        + "：放行" + quantity.getReleasedQuantity() + "，待判" + quantity.getPendingQuantity()
                        + "，拒绝" + quantity.getRejectedQuantity()));
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
     * 生产工单编辑
     *
     * @param mesTask
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void edit(MesTaskEntity mesTask) {
        if (mesTask == null || mesTask.getId() == null) {
            throw new CheckedException("生产工单不存在，无法编辑");
        }
        MesTaskEntity persisted = this.getById(mesTask.getId());
        if (persisted == null) {
            throw new CheckedException("生产工单不存在，无法编辑");
        }
        if (!MesTaskStatusEnum.CREATE.name().equals(persisted.getStatus())) {
            throw new CheckedException("生产工单任务已下达或开始，无法进行编辑操作");
        }
        mesTask.setStatus(persisted.getStatus());
        if (StrUtil.isNotBlank(persisted.getApsOperationSnapshot())) {
            mesTask.setPlanNo(persisted.getPlanNo());
            mesTask.setType(persisted.getType());
            mesTask.setMatCode(persisted.getMatCode());
            mesTask.setMatName(persisted.getMatName());
            mesTask.setMatSpec(persisted.getMatSpec());
            mesTask.setNoticeNum(persisted.getNoticeNum());
            mesTask.setPlanStartDate(persisted.getPlanStartDate());
            mesTask.setPlanEndDate(persisted.getPlanEndDate());
            mesTask.setWorkshopCode(persisted.getWorkshopCode());
            mesTask.setWorkshopName(persisted.getWorkshopName());
            mesTask.setProcessRouteCode(persisted.getProcessRouteCode());
            mesTask.setResourceType(persisted.getResourceType());
            mesTask.setResourceId(persisted.getResourceId());
            mesTask.setMaterialReservationSnapshot(persisted.getMaterialReservationSnapshot());
            mesTask.setApsOperationSnapshot(persisted.getApsOperationSnapshot());
            mesTask.setApsTaskId(persisted.getApsTaskId());
            mesTask.setApsBatchId(persisted.getApsBatchId());
            mesTask.setApsBatchNo(persisted.getApsBatchNo());
            mesTask.setApsBatchType(persisted.getApsBatchType());
            mesTask.setApsBatchFingerprint(persisted.getApsBatchFingerprint());
            mesTask.setFactoryCode(persisted.getFactoryCode());
        }
        mesTask.setUnInStockNum(BigDecimal.ZERO);
//        if (MesTaskTypeEnum.PRODUCT.name().equals(mesTask.getType())) {
//            mesTask.setUnInStockNum(mesTask.getNoticeNum());
//        }
        this.updateById(mesTask);
    }


    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    @Override
    public MesTaskDTO findBydId(Long id) {
        MesTaskEntity mesTask = this.getById(id);
        if (Objects.isNull(mesTask)) {
            throw new CheckedException("查询失败");
        }
        MesTaskDTO result = new MesTaskDTO();
        BeanUtil.copyProperties(mesTask, result);
        return result;
    }

    /**
     * 下达任务
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void issue(Long id) {
        List<MesProduceTaskEntity> saveTaskList = new ArrayList<>();
        MesTaskEntity mesTask = this.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getId, id).last("FOR UPDATE"));
        if (Objects.isNull(mesTask)) {
            throw new CheckedException("未找到生产工单");
        }
        if (!MesTaskStatusEnum.CREATE.name().equals(mesTask.getStatus())) {
            throw new CheckedException("生产工单已下达或状态已变化，禁止重复下达");
        }
        reworkMaterialPlans.assertReady(mesTask);
        if (produceTaskService.count(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTaskCode, mesTask.getCode())) > 0) {
            throw new CheckedException("生产工单已存在工序任务，禁止重复下达");
        }
        if (Objects.isNull(mesTask.getBomId())) {
            throw new CheckedException("请先维护bom信息");
        }
        if (Objects.isNull(mesTask.getProcessRouteId()) || StrUtil.isEmpty(mesTask.getProcessRouteSnapshot())) {
            throw new CheckedException("请先维护工艺路线信息");
        }
        // 根据工艺路线保存生产工序任务表
        List<MdmProcessRouteWorkingDTO> routeList = JSONUtil.toList(mesTask.getProcessRouteSnapshot(), MdmProcessRouteWorkingDTO.class);
        normalizeRouteGraph(routeList);
        boolean hasApsSnapshot = StrUtil.isNotBlank(mesTask.getApsOperationSnapshot());
        List<MesApsOperationSnapshotDTO> apsOperations = new ArrayList<>();
        if (hasApsSnapshot) {
            try {
                apsOperations = JSONUtil.toList(mesTask.getApsOperationSnapshot(), MesApsOperationSnapshotDTO.class);
            } catch (Exception ex) {
                throw new CheckedException("APS工序资源快照损坏，禁止下达生产任务");
            }
            if (CollUtil.isEmpty(apsOperations) || apsOperations.stream().anyMatch(item ->
                    Boolean.TRUE.equals(item.getHardBlocked()) || !Boolean.TRUE.equals(item.getLockStatus()))) {
                throw new CheckedException("APS工序尚未全部完成有效锁定，禁止下达生产任务");
            }
        }
        Map<String, List<MesApsOperationSnapshotDTO>> apsByProcess = apsOperations.stream()
                .filter(item -> StrUtil.isNotBlank(item.getProcessCode()))
                .collect(Collectors.groupingBy(MesApsOperationSnapshotDTO::getProcessCode));

        if (CollUtil.isNotEmpty(routeList)) {
            routeList.forEach(route -> {
                MesProduceTaskEntity saveTask = new MesProduceTaskEntity();
                saveTask.setCode(remoteSeqConfigService.create("mes_produce_task").getData());
                saveTask.setTaskCode(mesTask.getCode());
                saveTask.setMatCode(mesTask.getMatCode());
                saveTask.setMatName(mesTask.getMatName());
                saveTask.setMatSpec(mesTask.getMatSpec());
                saveTask.setRouteCode(StrUtil.blankToDefault(route.getProcessCode(), route.getRouteCode()));
                saveTask.setRouteName(StrUtil.blankToDefault(route.getProcessName(), route.getRouteName()));
                saveTask.setRouteType(route.getType());
                saveTask.setRouteOrder(route.getRouteOrder());
                saveTask.setPredecessorCodes(joinCodes(route.getPreRouteCode()));
                saveTask.setSuccessorCodes(joinCodes(route.getNextRouteCode()));
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
                String processCode = StrUtil.blankToDefault(route.getProcessCode(), route.getRouteCode());
                Integer operationSeq = Optional.ofNullable(route.getOrderNum()).orElse(route.getRouteOrder());
                List<MesApsOperationSnapshotDTO> candidates = apsByProcess.getOrDefault(processCode, List.of());
                MesApsOperationSnapshotDTO aps = candidates.stream()
                        .filter(item -> operationSeq != null && Objects.equals(operationSeq, item.getOperationSeq()))
                        .findFirst()
                        .orElse(candidates.size() == 1 ? candidates.get(0) : null);
                if (hasApsSnapshot && aps == null) {
                    throw new CheckedException(String.format("工序【%s】未匹配到唯一APS锁定结果，禁止下达生产任务", route.getRouteName()));
                }
                if (aps != null) {
                    if (!new LinkedHashSet<>(splitCodes(saveTask.getPredecessorCodes()))
                            .equals(new LinkedHashSet<>(splitCodes(aps.getPredecessorCodes())))) {
                        throw new CheckedException(String.format("工序【%s】的MES路线快照与APS前置关系不一致，禁止下达生产任务",
                                route.getRouteName()));
                    }
                    if (Boolean.TRUE.equals(saveTask.getOverlapEnabled()) != Boolean.TRUE.equals(aps.getOverlapEnabled())
                            || !sameDecimal(saveTask.getTransferBatchQuantity(), aps.getTransferBatchQuantity())) {
                        throw new CheckedException(String.format("工序【%s】的MES路线快照与APS转移批量策略不一致，禁止下达生产任务",
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
                    if ("STATION".equalsIgnoreCase(aps.getResourceType()) && StrUtil.isNotBlank(aps.getResourceCode())) {
                        saveTask.setPlannedWorksiteCode(aps.getResourceCode());
                        saveTask.setPlannedWorksiteName(aps.getResourceName());
                        saveTask.setWorksiteCodes(aps.getResourceCode());
                        saveTask.setWorksiteNames(StrUtil.blankToDefault(aps.getResourceName(), aps.getResourceCode()));
                    }
                }

                saveTask.setLastRoute(splitCodes(saveTask.getSuccessorCodes()).isEmpty());
                saveTask.setProcessRouteId(mesTask.getProcessRouteId());
                saveTask.setProcessRouteSnapshot(mesTask.getProcessRouteSnapshot());
                saveTask.setProcessRouteCode(mesTask.getProcessRouteCode());
                saveTask.setNoticeNum(mesTask.getNoticeNum());
                saveTask.setNoticeDate(LocalDateTime.now());
                saveTask.setStatus(MesProduceTaskStatusEnum.WAIT.name());
                saveTask.setWorkshopCode(mesTask.getWorkshopCode());
                saveTask.setWorkshopName(mesTask.getWorkshopName());
                saveTask.setWrapCode(mesTask.getWrapCode());
                saveTask.setReportType(mesTask.getType());
                saveTask.setIsInspection(route.getIsInspection());
                saveTask.setFactoryCode(mesTask.getFactoryCode());
                saveTaskList.add(saveTask);
            });
        }
        // 保存工序任务
        produceTaskService.saveBatch(saveTaskList);

        // 更新生产工单状态
        mesTask.setStatus(MesTaskStatusEnum.ISSUE.name());
        this.updateById(mesTask);

        if (StrUtil.isNotEmpty(mesTask.getPlanNo())) {
            planMesStatusUpdate(mesTask);
        }

        // 生成质检任务
        genInspectionTask(saveTaskList, mesTask.getMatCode());
    }

    /** 下达MES前再次校验路线快照DAG，并由前置关系重建后置关系和拓扑层级。 */
    private void normalizeRouteGraph(List<MdmProcessRouteWorkingDTO> routeList) {
        if (CollUtil.isEmpty(routeList)) {
            return;
        }
        Map<String, MdmProcessRouteWorkingDTO> byCode = new LinkedHashMap<>();
        for (MdmProcessRouteWorkingDTO route : routeList) {
            String code = StrUtil.blankToDefault(route.getProcessCode(), route.getRouteCode());
            if (StrUtil.isBlank(code) || byCode.putIfAbsent(code, route) != null) {
                throw new CheckedException("生产工艺路线快照存在空编码或重复工序：" + code);
            }
        }
        Map<String, LinkedHashSet<String>> predecessors = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> successors = new LinkedHashMap<>();
        byCode.keySet().forEach(code -> {
            predecessors.put(code, new LinkedHashSet<>());
            successors.put(code, new LinkedHashSet<>());
        });
        boolean hasExplicitRelation = routeList.stream().anyMatch(route ->
                route.getPreRouteCode() != null && route.getPreRouteCode().length > 0
                        || route.getNextRouteCode() != null && route.getNextRouteCode().length > 0);
        List<MdmProcessRouteWorkingDTO> legacyOrder = routeList.stream().sorted(Comparator
                .comparingInt((MdmProcessRouteWorkingDTO route) -> Optional.ofNullable(route.getOrderNum())
                        .orElse(Optional.ofNullable(route.getRouteOrder()).orElse(Integer.MAX_VALUE)))
                .thenComparing(route -> StrUtil.blankToDefault(route.getProcessCode(), route.getRouteCode()))).toList();
        if (!hasExplicitRelation && legacyOrder.size() > 1) {
            for (int index = 1; index < legacyOrder.size(); index++) {
                legacyOrder.get(index).setPreRouteCode(new String[]{StrUtil.blankToDefault(
                        legacyOrder.get(index - 1).getProcessCode(), legacyOrder.get(index - 1).getRouteCode())});
            }
        }
        byCode.forEach((code, route) -> splitCodes(joinCodes(route.getPreRouteCode())).forEach(predecessor -> {
            if (!byCode.containsKey(predecessor)) {
                throw new CheckedException("工序[" + code + "]引用不存在的前置工序[" + predecessor + "]");
            }
            if (code.equals(predecessor)) {
                throw new CheckedException("工序[" + code + "]存在自环");
            }
            predecessors.get(code).add(predecessor);
            successors.get(predecessor).add(code);
        }));
        long terminalCount = successors.values().stream().filter(Set::isEmpty).count();
        if (terminalCount != 1) {
            throw new CheckedException("生产工艺路线必须且只能有一道最终工序，禁止下达");
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
        byCode.forEach((code, route) -> {
            route.setPreRouteCode(predecessors.get(code).toArray(String[]::new));
            route.setNextRouteCode(successors.get(code).toArray(String[]::new));
            route.setOrderNum(levels.get(code));
            route.setRouteOrder(levels.get(code));
        });
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
     * 生成质检任务
     *
     * @param saveTaskList 生产任务列表
     * @param matCode      物料编码
     */
    private void genInspectionTask(List<MesProduceTaskEntity> saveTaskList, String matCode) {
        // 根据物料查询质检任务生成规则
        MesInspectionRuleEntity inspectionRule = inspectionRuleService.getRuleByMatCode(matCode);
        List<MesInspectionGenRecordEntity> recordList = new ArrayList<>();
        // 生成质检任务
        saveTaskList.forEach(task -> {
            // 需要质检
            if ("1".equals(task.getIsInspection())) {
                // 设置了规则才创建
                if (Objects.nonNull(inspectionRule) && containsInspectionType(inspectionRule,
                        ProcedureLabelTypeEnum.FIRST)) {
                    String sourceRequestId = "MES_QMS:" + task.getCode() + ":FIRST";
                    InspectionProcedureCreateParam createParam = null;
                    R<Long> procedureResult = null;
                    String genFlag = "1";
                    String msg = "生成质检任务成功";
                    try {
                        QmsQualityStandardEntity standard = remoteQualityStandardService.getRandomStandard(
                                new QmsQualityStandardEntity().setMatCode(task.getMatCode())
                                        .setInspectionType(InspectionTaskTypeEnum.PROCEDURE.name())).getData();
                        if (standard == null) {
                            throw new CheckedException("未匹配到已发布的工序质检标准");
                        }
                        createParam = buildCreateParam(task, standard, sourceRequestId);
                        procedureResult = remoteProcedureService.create(createParam);
                        if (procedureResult == null || procedureResult.getData() == null) {
                            throw new CheckedException(procedureResult == null ? "QMS未返回结果" : procedureResult.getMsg());
                        }
                    } catch (Exception exception) {
                        genFlag = "0";
                        msg = "生成质检任务失败：" + rootMessage(exception);
                    }
                    MesInspectionGenRecordEntity record = new MesInspectionGenRecordEntity();
                    record.setTaskCode(task.getTaskCode());
                    record.setProduceTaskCode(task.getCode());
                    record.setInspectionId(procedureResult == null ? null : procedureResult.getData());
                    record.setSourceRequestId(sourceRequestId);
                    record.setRuleCode(inspectionRule.getCode());
                    record.setRuleName(inspectionRule.getName());
                    record.setInspectionType(ProcedureLabelTypeEnum.FIRST.name());
                    record.setGenFlag(genFlag);
                    record.setGenRemark(msg);
                    record.setGateStatus("1".equals(genFlag) ? QualityGateStatusEnum.PENDING.name()
                            : QualityGateStatusEnum.ERROR.name());
                    record.setSyncMessage(msg);
                    record.setBlockedCount(0);
                    record.setGenTime(LocalDateTime.now());
                    try {
                        record.setGenShiftCode(remoteClassesService.getRuleBanci(LocalDateTime.now()).getData());
                    } catch (Exception exception) {
                        record.setSyncMessage(msg + "；班次读取失败：" + rootMessage(exception));
                    }
                    if (containsInspectionType(inspectionRule, ProcedureLabelTypeEnum.MIDDLE)) {
                        if ("HOUR".equals(inspectionRule.getMethod())) {
                            record.setGenTime(LocalDateTime.now());
                            record.setNextTime(calculateNextTime(LocalDateTime.now(), inspectionRule.getNum()));
                        } else {
                            // 获取下一班次
                            List<MdmClassesEntity> banciList = remoteClassesService.getClassesBydate(DateUtil.formatDateTime(new Date()), 2).getData();
                            if (CollUtil.isEmpty(banciList) || banciList.size() < 2) {
                                record.setGenFlag("0");
                                record.setGateStatus(QualityGateStatusEnum.ERROR.name());
                                record.setGenRemark("中检班次计划生成失败：未找到下一班次");
                            } else {
                                record.setNextShift(banciList.get(1).getShiftCode());
                            }
                        }
                    }
                    record.setFactoryCode(task.getFactoryCode());
                    recordList.add(record);
                }
            }
        });
        genRecordService.saveBatch(recordList);
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
     * 计算下次生成时间
     *
     * @param currentTime   当前时间
     * @param intervalHours 间隔小时数
     * @return 下次生成时间
     */
    private LocalDateTime calculateNextTime(LocalDateTime currentTime, BigDecimal intervalHours) {
        if (currentTime == null || intervalHours == null) {
            return currentTime;
        }
        return currentTime.plusHours(intervalHours.longValue());
    }

    /**
     * 关闭任务
     *
     * @param id
     */
    @Override
    public void close(Long id) {
        this.update(new LambdaUpdateWrapper<MesTaskEntity>().eq(MesTaskEntity::getId, id)
                .set(MesTaskEntity::getStatus, MesTaskStatusEnum.CLOSE.name()));
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
    @Transactional(rollbackFor = Exception.class)
    public Boolean inWms(MesTaskInWmsDTO mesTaskInWmsDTO) {
        if (mesTaskInWmsDTO == null || mesTaskInWmsDTO.getId() == null
                || mesTaskInWmsDTO.getInWmsNum() == null || mesTaskInWmsDTO.getInWmsNum().signum() <= 0
                || StrUtil.isBlank(mesTaskInWmsDTO.getWarehouseCode())) {
            throw new CheckedException("入库工单、仓库和正数入库数量不能为空");
        }
        MesTaskEntity mesTask = Optional.ofNullable(this.getById(mesTaskInWmsDTO.getId())).orElseThrow(() -> new CheckedException("工单查询失败"));
        String requestId = StrUtil.trim(mesTaskInWmsDTO.getRequestId());
        if (StrUtil.isNotBlank(mesTask.getApsBatchNo()) && StrUtil.isBlank(requestId)) {
            throw new CheckedException("APS批次工单入库必须提供永久幂等号");
        }
        if (StrUtil.isNotBlank(requestId)) {
            if (requestId.length() > 64) throw new CheckedException("入库幂等号长度不能超过64");
            String fingerprint = inWmsFingerprint(mesTaskInWmsDTO);
            taskInWmsReceiptMapper.lockRequest(requestId);
            MesTaskInWmsReceiptEntity existing = taskInWmsReceiptMapper.selectOne(
                    new LambdaQueryWrapper<MesTaskInWmsReceiptEntity>()
                            .eq(MesTaskInWmsReceiptEntity::getRequestId, requestId), false);
            if (existing != null) {
                if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
                    throw new CheckedException("入库幂等号已使用且载荷不一致：" + requestId);
                }
                return true;
            }
            if (StrUtil.isNotBlank(mesTask.getApsBatchNo())) {
                baseMapper.acquireApsBatchTransactionLock(mesTask.getApsBatchNo());
                mesTask = Optional.ofNullable(this.getById(mesTask.getId()))
                        .orElseThrow(() -> new CheckedException("工单查询失败"));
            }
        }
        // 处理待入库数量
        BigDecimal unInStockNum = Optional.ofNullable(mesTask.getUnInStockNum()).orElseThrow(() -> new CheckedException("待入库数量不存在"));
        // 检查入库数量是否超过待入库数量
        if (mesTaskInWmsDTO.getInWmsNum().compareTo(unInStockNum) > 0) {
            throw new CheckedException("入库数量大于工单待入库数量");
        }
        // 更新待入库数量和已入库数量
        mesTask.setUnInStockNum(unInStockNum.subtract(mesTaskInWmsDTO.getInWmsNum()));
        mesTask.setInStockNum(Optional.ofNullable(mesTask.getInStockNum()).orElse(BigDecimal.ZERO).add(mesTaskInWmsDTO.getInWmsNum()));

        // 推送仓库
        this.wmsProductInNotice(mesTask, mesTaskInWmsDTO);
        // 推送生产计划模块
        if (StrUtil.isNotBlank(mesTask.getPlanNo())) {
            planMesFinishUpdate(mesTask, mesTaskInWmsDTO);
        }
        // 更新工单信息
        boolean updated = this.updateById(mesTask);
        if (updated && StrUtil.isNotBlank(requestId)) {
            taskInWmsReceiptMapper.insert(new MesTaskInWmsReceiptEntity()
                    .setRequestId(requestId).setRequestFingerprint(inWmsFingerprint(mesTaskInWmsDTO))
                    .setMesTaskId(mesTask.getId()).setMesTaskCode(mesTask.getCode())
                    .setApsBatchNo(mesTask.getApsBatchNo()).setWarehouseCode(mesTaskInWmsDTO.getWarehouseCode())
                    .setInStockQuantity(mesTaskInWmsDTO.getInWmsNum()).setHandledTime(LocalDateTime.now())
                    .setFactoryCode(mesTask.getFactoryCode()));
        }
        return updated;
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
    private void wmsProductInNotice(MesTaskEntity task, MesTaskInWmsDTO mesTaskInWmsDTO) {
        // 查询主数据物料信息
        MdmMaterialEntity mat = Optional.ofNullable(matService.getDetails(task.getMatCode()).getData())
                .orElseThrow(() -> new CheckedException("物料信息查询失败"));
        // 主表处理逻辑
        double inNum = mesTaskInWmsDTO.getInWmsNum().doubleValue();
        WmsInNoticeEntity inNotice = new WmsInNoticeEntity();
        if (StrUtil.isNotBlank(mesTaskInWmsDTO.getRequestId())) {
            inNotice.setSourceRequestId("MES_TASK_IN_WMS:" + mesTaskInWmsDTO.getRequestId().trim());
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
        inNoticeDetail.setMatUnit(mat.getBasicUnit());
        inNoticeDetail.setWarehouseCode(mesTaskInWmsDTO.getWarehouseCode());
        inNoticeDetail.setWarehouseName(mesTaskInWmsDTO.getWarehouseName());
        inNoticeDetail.setNoticeInNum(inNum);
        inNoticeDetail.setInNum(0d);
        inNoticeDetail.setNotInNum(inNum);
        inNoticeDetail.setOperateNum(inNum);
        inNoticeDetail.setFactoryCode(task.getFactoryCode());
        inNotice.setDetailList(Collections.singletonList(inNoticeDetail));
        inNoticeService.save(inNotice);
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
                apsBatchAllocationMapper.updateById(allocation);
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
        Boolean statusUpdate = callbacks.isEmpty() || Boolean.TRUE.equals(remotePlnIssueService.edit(callbacks).getData());
        if (statusUpdate == null || !statusUpdate) {
            throw new CheckedException(String.format("%s的工单推送生产计划模块失败", task.getCode()));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void feedbackPlanCompletion(MesTaskEntity task, BigDecimal completeNum) {
        if (task == null || StrUtil.isBlank(task.getPlanNo())) return;
        List<MesApsBatchAllocationEntity> allocations = batchAllocations(task, true);
        List<OmsOrderLifeCycleDTO> callbacks = new ArrayList<>();
        if (!allocations.isEmpty()) {
            BigDecimal totalAllocated = allocations.stream().map(MesApsBatchAllocationEntity::getAllocatedQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal completed = Objects.requireNonNullElse(completeNum, BigDecimal.ZERO);
            if (completed.signum() < 0 || completed.compareTo(totalAllocated) > 0) {
                throw new CheckedException("APS批次完工数量不能为负或超过来源计划分配总量");
            }
            BigDecimal assigned = BigDecimal.ZERO;
            for (int index = 0; index < allocations.size(); index++) {
                MesApsBatchAllocationEntity allocation = allocations.get(index);
                BigDecimal target = index == allocations.size() - 1 ? completed.subtract(assigned)
                        : completed.multiply(allocation.getAllocatedQuantity())
                        .divide(totalAllocated, 8, RoundingMode.HALF_UP).min(allocation.getAllocatedQuantity());
                assigned = assigned.add(target);
                allocation.setCompletedQuantity(target);
                apsBatchAllocationMapper.updateById(allocation);
                callbacks.add(lifeCycleCallback(task, allocation.getIssueNo(), allocation.getIssueId(),
                        "COMPLETE", "3", OrderLongStateEnum.SCHEDULE.getCode(), null, LocalDate.now(),
                        String.format("APS批次【%s】MES工单【%s】已完工，批次完成量【%s】，来源归属完成量【%s】，操作时间【%s】",
                                task.getApsBatchNo(), task.getCode(), completed, target, LocalDate.now())));
            }
        } else {
            callbacks.add(lifeCycleCallback(task, task.getPlanNo(), null, "COMPLETE", "3",
                    OrderLongStateEnum.SCHEDULE.getCode(), null, LocalDate.now(),
                    String.format("工单编号【%s】,已完工,总生产数量【%s】,操作时间【%s】",
                            task.getCode(), completeNum, LocalDate.now())));
        }
        if (!Boolean.TRUE.equals(remotePlnIssueService.edit(callbacks).getData())) {
            throw new CheckedException(String.format("%s的工单推送生产计划模块失败", task.getCode()));
        }
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
                .map(String::trim).anyMatch(value -> type.name().equalsIgnoreCase(value)
                        || type.getName().equals(value));
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

    @Override
    public MesTaskDTO getByTaskCode(String taskCode) {
        return baseMapper.getByTaskCode(taskCode);
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
}
