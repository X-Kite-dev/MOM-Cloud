package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.dto.MdmToolingAvailabilityQueryDTO;
import com.kitex.mom.mdm.dto.MdmToolingCandidateDTO;
import com.kitex.mom.mdm.dto.MdmToolingUsageLineDTO;
import com.kitex.mom.mdm.dto.MdmToolingUsageRequestDTO;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.mdm.feign.RemoteMdmToolingService;
import com.kitex.mom.mdm.feign.RemoteWorkCenterService;
import com.kitex.mom.mes.batch.BatchGenerator;
import com.kitex.mom.mes.batch.BatchGeneratorService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesDeviceGateStageEnum;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.MesTaskStatusEnum;
import com.kitex.mom.mes.enums.WorksiteTypeEnum;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import com.kitex.mom.mes.util.MesFrozenBomProjection;
import com.kitex.mom.mes.util.MesWorksiteTriggerProjection;
import com.kitex.mom.mes.util.MesTaskUtils;
import com.kitex.mom.plan.feign.RemotePlnIssueService;
import com.kitex.mom.tpm.api.feign.RemoteDeviceCheckService;
import com.kitex.mom.tpm.api.feign.RemoteTpmDeviceService;
import com.kitex.mom.tpm.dto.DeviceChangeMoldDto;
import com.kitex.mom.tpm.dto.DeviceCheckTaskStartDto;
import com.kitex.mom.tpm.dto.TpmDevicePauseCommand;
import com.kitex.mom.tpm.dto.TpmDevicePauseReceipt;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 生产派工
 *
 * @author kitex
 * @since 2025-05-06
 */
@Service
@AllArgsConstructor
public class MesTaskArrangeServiceImpl extends ServiceImpl<MesTaskArrangeMapper, MesTaskArrangeEntity> implements MesTaskArrangeService {

    private final MesTaskService mesTaskService;
    private final MesProduceTaskService produceTaskService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final MesWarehouseFeedService feedService;
    private final RemoteWorkCenterService workCenterService;
    private final RemoteDeviceService deviceService;
    private final BatchGeneratorService batchGeneratorService;
    private final RemoteTpmDeviceService remoteTpmDeviceService;
    private final RemoteDeviceCheckService remoteDeviceCheckService;
    private final MesWorksiteMoldService worksiteMoldService;
    private final MesTaskDeviceRecordService deviceRecordService;
    private final RemotePlnIssueService remotePlnIssueService;
    private final RemoteMdmToolingService remoteMdmToolingService;
    private final MesToolingExecutionMapper toolingExecutionMapper;
    private final MesQualityGateService qualityGateService;
    private final MesQualityQuantityService qualityQuantityService;
    private final MesDeviceGateService deviceGateService;
    private final MesSopExecutionService sopExecutionService;
    private final MesMaterialStartGateService materialStartGate;
    private final MesAndonProductionReleaseService andonProductionReleaseService;
    private final MesRepairProductionReleaseService repairProductionReleaseService;
    private final MesRepairResumeEventService repairResumeEventService;
    private final MesChangePointHandleService changePointHandleService;
    private final MesDispatchReturnService dispatchReturnService;
    private final MesTaskExecutionSegmentMapper executionSegmentMapper;
    private final MesChangeoverExecutionMapper changeoverExecutionMapper;
    private final MesExecutionTemplateService executionTemplateService;
    private final MesActualFactoryAccessService actualFactoryAccess;
    private final MesIdentifierBindingGroupService identifierBindingGroupService;
    private final MesExecutionTaskLock executionTaskLock;
    private final MesTerminalExecutionGuard terminalExecutionGuard;
    private final MesStartContractGuard startContractGuard;
    private final MesWorkpieceTimeService workpieceTimeService;
    private final MesDispatchCorrectionMapper correctionMapper;
    private final MesDispatchCompletionGuard completionGuard;

    private static String workbenchFilter(String value, int maxLength, String label) {
        String normalized = StrUtil.trimToNull(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new CheckedException(label + "过长");
        }
        return normalized;
    }

    @Override
    @Transactional(readOnly = true)
    public IPage<MesDispatchWorkbenchDTO> workbenchPage(long current, long size, String taskCode,
                                                        String matCode, String workshopCode, String status) {
        String factory = actualFactoryAccess.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        if (current < 1 || size < 1 || size > 100) {
            throw new CheckedException("分页参数不合法，单页最多查询100条工单");
        }
        taskCode = workbenchFilter(taskCode, 100, "工单编码");
        matCode = workbenchFilter(matCode, 100, "物料编码");
        workshopCode = workbenchFilter(workshopCode, 100, "车间编码");
        status = workbenchFilter(status, 30, "工单状态");
        if (status != null) {
            try {
                MesTaskStatusEnum.valueOf(status);
            } catch (IllegalArgumentException exception) {
                throw new CheckedException("工单状态不合法");
            }
        }

        LambdaQueryWrapper<MesTaskEntity> filter = new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant)
                .eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getDelFlag, "0")
                .like(StrUtil.isNotBlank(taskCode), MesTaskEntity::getCode, taskCode)
                .like(StrUtil.isNotBlank(matCode), MesTaskEntity::getMatCode, matCode)
                .eq(StrUtil.isNotBlank(workshopCode), MesTaskEntity::getWorkshopCode, workshopCode)
                .eq(StrUtil.isNotBlank(status), MesTaskEntity::getStatus, status)
                .select(MesTaskEntity::getCode, MesTaskEntity::getStatus,
                        MesTaskEntity::getMatCode, MesTaskEntity::getMatName,
                        MesTaskEntity::getWorkshopCode, MesTaskEntity::getWorkshopName,
                        MesTaskEntity::getNoticeNum, MesTaskEntity::getCompleteNum,
                        MesTaskEntity::getPlanStartDate, MesTaskEntity::getPlanEndDate)
                .orderByDesc(MesTaskEntity::getCreateTime)
                .orderByDesc(MesTaskEntity::getId);
        IPage<MesTaskEntity> orders = mesTaskService.page(new Page<>(current, size), filter);
        Page<MesDispatchWorkbenchDTO> result = new Page<>(current, size, orders.getTotal());
        if (orders.getRecords().isEmpty()) {
            return result;
        }

        List<String> codes = orders.getRecords().stream().map(MesTaskEntity::getCode).distinct().toList();
        Map<String, MesDispatchWorkbenchDTO.ProcessCount> processes = baseMapper
                .countProcessTasks(tenant, factory, codes).stream()
                .collect(Collectors.toMap(MesDispatchWorkbenchDTO.ProcessCount::getTaskCode, item -> item));
        Map<String, MesDispatchWorkbenchDTO.DispatchSummary> dispatches = baseMapper
                .summarizeDispatches(tenant, factory, codes).stream()
                .collect(Collectors.toMap(MesDispatchWorkbenchDTO.DispatchSummary::getTaskCode, item -> item));
        List<MesDispatchWorkbenchDTO> rows = new ArrayList<>(orders.getRecords().size());
        for (MesTaskEntity order : orders.getRecords()) {
            MesDispatchWorkbenchDTO.ProcessCount process = processes.get(order.getCode());
            MesDispatchWorkbenchDTO.DispatchSummary dispatch = dispatches.get(order.getCode());
            rows.add(new MesDispatchWorkbenchDTO()
                    .setTaskCode(order.getCode()).setStatus(order.getStatus())
                    .setMatCode(order.getMatCode()).setMatName(order.getMatName())
                    .setWorkshopCode(order.getWorkshopCode()).setWorkshopName(order.getWorkshopName())
                    .setNoticeNum(order.getNoticeNum()).setCompleteNum(order.getCompleteNum())
                    .setPlanStartDate(order.getPlanStartDate()).setPlanEndDate(order.getPlanEndDate())
                    .setProcessTaskCount(process == null ? 0L : process.getProcessTaskCount())
                    .setActiveProcessCount(dispatch == null ? 0L : dispatch.getActiveProcessCount())
                    .setDispatchCount(dispatch == null ? 0L : dispatch.getDispatchCount())
                    .setActiveDispatchCount(dispatch == null ? 0L : dispatch.getActiveDispatchCount())
                    .setLatestDispatchTime(dispatch == null ? null : dispatch.getLatestDispatchTime()));
        }
        result.setRecords(rows);
        return result;
    }

    /**
     * 批量派工
     *
     * @param dto
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void arrange(MesTaskArrangeDTO dto) {
        arrangeInternal(dto, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void arrangeBatch(List<MesTaskArrangeDTO> batches) {
        if (CollUtil.isEmpty(batches) || batches.size() > 20 || batches.stream().anyMatch(Objects::isNull)) {
            throw new CheckedException("请选择同一工单的有效班次，单次最多派工20个班次");
        }
        String taskCode = batches.get(0).getCode();
        if (StrUtil.isBlank(taskCode) || batches.stream().anyMatch(item -> !taskCode.equals(item.getCode()))) {
            throw new CheckedException("多班次派工只能提交同一生产工单");
        }
        Set<String> shifts = new HashSet<>();
        for (MesTaskArrangeDTO batch : batches) {
            if (StrUtil.isBlank(batch.getShiftCode()) || !shifts.add(batch.getShiftCode())) {
                throw new CheckedException("派工班次不能为空或重复");
            }
            arrangeInternal(batch, null);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void arrangeCorrection(MesTaskArrangeDTO dto, Long correctionId) {
        if (correctionId == null) throw new CheckedException("修正补派必须提供精确申请");
        arrangeInternal(dto, correctionId);
    }

    private void arrangeInternal(MesTaskArrangeDTO dto, Long correctionId) {
        String factory = actualFactoryAccess.require("write");
        Long tenant = TenantContextHolder.getTenantId();
        if (dto == null || StrUtil.isBlank(dto.getCode()) || StrUtil.isBlank(dto.getShiftCode())) {
            throw new CheckedException("请选择生产工单和派工班次");
        }
        // 先取稳定工单锁再查重；不提前反向锁历史派工，保持现有报工入口安全。
        identifierBindingGroupService.lockDispatchTask(dto.getCode());
        long existCount = this.count(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant)
                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getShiftCode, dto.getShiftCode())
                .eq(MesTaskArrangeEntity::getTaskCode, dto.getCode())
                .notIn(MesTaskArrangeEntity::getStatus,
                        MesProduceTaskStatusEnum.CLOSE.name(), MesProduceTaskStatusEnum.FINISH.name()));
        if (correctionId == null && existCount > 0) {
            throw new RuntimeException("该班次已完成派工,无法重复派工");
        }
        MomUser currentUser = SecurityUtils.getUser();
        if (correctionId != null) {
            var correction = correctionMapper.lockedRequest(tenant, factory, correctionId);
            if (correction == null || !"SUBMITTED".equals(correction.getStatus())
                    || Objects.equals(correction.getApplicantId(), currentUser.getId())
                    || Objects.equals(correction.getApplicantCode(), currentUser.getUsername())
                    || !Objects.equals(correction.getTaskCode(), dto.getCode())
                    || !Objects.equals(correction.getTargetShiftCode(), dto.getShiftCode())
                    || !Objects.equals(correction.getTargetShiftName(), dto.getShiftName())
                    || dto.getTaskArrangeList() == null || dto.getTaskArrangeList().size() != 1) {
                throw new CheckedException("仅异人审批原待审修正可补派精确目标");
            }
            var old = correctionMapper.arrange(tenant, factory, correction.getArrangeId());
            var input = dto.getTaskArrangeList().get(0);
            if (old == null || !"CLOSE".equals(old.getStatus()) || !Objects.equals(old.getTaskCode(), dto.getCode())
                    || !Objects.equals(old.getProduceTaskCode(), correction.getProduceTaskCode())
                    || !Objects.equals(input.getProduceTaskCode(), correction.getProduceTaskCode())
                    || !Objects.equals(input.getWorkerCode(), old.getWorkerCode())
                    || !Objects.equals(input.getWorkerName(), old.getWorkerName())
                    || !Objects.equals(input.getWorksiteCodes(), correction.getTargetWorksiteCodes())
                    || correctionMapper.replacement(tenant, factory, correctionId) != null
                    || correctionMapper.otherActive(tenant, factory, old.getTaskCode(), old.getProduceTaskCode(), old.getId()) > 0) {
                throw new CheckedException("修正来源未关闭、目标变化、已有替代或同工序活动派工");
            }
        }
        List<MesTaskArrangeEntity> taskArrangeList = dto.getTaskArrangeList();
        if (CollUtil.isEmpty(taskArrangeList)) {
            throw new RuntimeException("请维护派工信息");
        }

        List<MesTaskEntity> mainTasks = mesTaskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, dto.getCode()).last("LIMIT 2"));
        if (mainTasks.size() != 1) {
            throw new CheckedException("当前实际组织的生产工单不存在或编码不唯一，派工失败");
        }
        MesTaskEntity mainTask = mainTasks.get(0);
        if (terminalParent(mainTask.getStatus())) {
            throw new CheckedException("已关闭或完工的生产工单不能重新派工");
        }
        List<String> requestedProduceTaskCodes = taskArrangeList.stream()
                .map(MesTaskArrangeEntity::getProduceTaskCode)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        if (requestedProduceTaskCodes.size() != taskArrangeList.size()) {
            throw new CheckedException("派工工序任务不能为空或重复");
        }
        Map<String, MesProduceTaskEntity> produceTaskMap = produceTaskService.list(
                        new LambdaQueryWrapper<MesProduceTaskEntity>()
                                .eq(MesProduceTaskEntity::getTenantId, tenant)
                                .eq(MesProduceTaskEntity::getFactoryCode, factory)
                                .eq(MesProduceTaskEntity::getTaskCode, mainTask.getCode())
                                .in(MesProduceTaskEntity::getCode, requestedProduceTaskCodes))
                .stream().collect(Collectors.toMap(MesProduceTaskEntity::getCode, item -> item));
        if (produceTaskMap.size() != requestedProduceTaskCodes.size()) {
            throw new CheckedException("部分工序任务不存在，派工失败");
        }
        List<String> produceTaskCodes = new ArrayList<>();
        List<MesTaskArrangeEntity> finalTaskArrangeList = new ArrayList<>();

        for (MesTaskArrangeEntity task : taskArrangeList) {
            MesProduceTaskEntity produceTask = produceTaskMap.get(task.getProduceTaskCode());
            if (!Objects.equals(mainTask.getCode(), produceTask.getTaskCode())) {
                throw new CheckedException("工序任务不属于当前生产工单，派工失败");
            }
            if (StrUtil.isBlank(task.getWorkerCode())) {
                throw new CheckedException("未绑定执行员工，派工失败");
            }
            if (StrUtil.isNotBlank(produceTask.getPlannedWorkerCode())
                    && !new LinkedHashSet<>(splitCsv(produceTask.getPlannedWorkerCode()))
                    .equals(new LinkedHashSet<>(splitCsv(task.getWorkerCode())))) {
                throw new CheckedException(String.format("工序【%s】已由APS锁定员工【%s】，如需调整请返回APS重排",
                        produceTask.getRouteName(), produceTask.getPlannedWorkerName()));
            }
            if (StrUtil.isBlank(task.getWorksiteCodes())) {
                throw new RuntimeException("未绑定工作中心，派工失败");
            }
            List<String> allowedWorksites = splitCsv(produceTask.getWorksiteCodes());
            List<String> selectedWorksites = splitCsv(task.getWorksiteCodes());
            if (selectedWorksites.isEmpty() || !allowedWorksites.containsAll(selectedWorksites)) {
                throw new CheckedException(String.format("工序【%s】选择了未授权工位，派工失败", produceTask.getRouteName()));
            }
            if (StrUtil.isNotBlank(produceTask.getPlannedWorksiteCode())
                    && (selectedWorksites.size() != 1
                    || !produceTask.getPlannedWorksiteCode().equals(selectedWorksites.get(0)))) {
                throw new CheckedException(String.format("工序【%s】已由APS锁定工位【%s】，如需调整请返回APS重排",
                        produceTask.getRouteName(), produceTask.getPlannedWorksiteName()));
            }
            MesTaskArrangeEntity newTask = new MesTaskArrangeEntity();
            BeanUtil.copyProperties(produceTask, newTask);
            newTask.setId(null);
            newTask.setCorrectionRequestId(correctionId);
            newTask.setProduceTaskCode(produceTask.getCode());
            MesFrozenExecutionGraph.assertArrangement(produceTask, newTask);
            newTask.setWorkerCode(StrUtil.isNotBlank(produceTask.getPlannedWorkerCode())
                    ? produceTask.getPlannedWorkerCode() : task.getWorkerCode());
            newTask.setWorkerName(StrUtil.isNotBlank(produceTask.getPlannedWorkerCode())
                    ? produceTask.getPlannedWorkerName() : task.getWorkerName());
            newTask.setWorksiteCodes(String.join(",", selectedWorksites));
            newTask.setWorksiteNames(resolveSelectedValues(produceTask.getWorksiteCodes(),
                    produceTask.getWorksiteNames(), selectedWorksites));
            newTask.setWorksiteTypes(MesWorksiteTriggerProjection.forDispatch(produceTask.getWorksiteCodes(),
                    produceTask.getWorksiteTypes(), selectedWorksites));
            newTask.setPlannedStartTime(produceTask.getPlannedStartTime());
            newTask.setPlannedEndTime(produceTask.getPlannedEndTime());
            newTask.setPlannedWorksiteCode(produceTask.getPlannedWorksiteCode());
            newTask.setPlannedWorksiteName(produceTask.getPlannedWorksiteName());
            newTask.setPlannedWorkerCode(produceTask.getPlannedWorkerCode());
            newTask.setPlannedWorkerName(produceTask.getPlannedWorkerName());
            newTask.setPlannedDeviceCode(produceTask.getPlannedDeviceCode());
            newTask.setPlannedDeviceName(produceTask.getPlannedDeviceName());
            newTask.setPlannedResourceSlotNo(produceTask.getPlannedResourceSlotNo());
            newTask.setPlannedResourceGroupCode(produceTask.getPlannedResourceGroupCode());
            newTask.setPlannedResourceGroupName(produceTask.getPlannedResourceGroupName());
            newTask.setPlannedResourceGroupCapacity(produceTask.getPlannedResourceGroupCapacity());
            newTask.setPlannedResourceGroupSlotNo(produceTask.getPlannedResourceGroupSlotNo());
            newTask.setPlannedToolingCodes(produceTask.getPlannedToolingCodes());
            newTask.setPlannedToolingNames(produceTask.getPlannedToolingNames());
            newTask.setToolingAllocationSnapshot(produceTask.getToolingAllocationSnapshot());
            newTask.setToolingLifeUsageSnapshot(produceTask.getToolingLifeUsageSnapshot());
            newTask.setApsOperationSnapshot(produceTask.getApsOperationSnapshot());

            // 生成新的编码
            newTask.setCode(remoteSeqConfigService.create("mes_task_arrange").getData());
            newTask.setDispatcherCode(currentUser.getUsername());
            newTask.setDispatcherName(currentUser.getName());
            newTask.setDispatcherTime(LocalDateTime.now());
            newTask.setShiftCode(dto.getShiftCode());
            newTask.setShiftName(dto.getShiftName());
            newTask.setStatus(MesProduceTaskStatusEnum.UN_RECEIVE.name());

            // 设置批次号
            BatchGenerator generator = batchGeneratorService.getBatchGenerator(mainTask.getBatchRuleCode());
            if (generator == null) {
                throw new RuntimeException("未找到对应的批次生成器，请检查批次规则配置");
            }
            newTask.setBatchNo(generator.generateBatchNumber(mainTask.getCode(), newTask.getShiftCode()));

            // 设置其他必要信息
            newTask.setWorkshopCode(mainTask.getWorkshopCode());
            newTask.setWorkshopName(mainTask.getWorkshopName());
            newTask.setWrapRuleCode(mainTask.getWrapCode());
            newTask.setWrapCapacity(mainTask.getWrapCapacity());
            newTask.setWrapCode(null);
            newTask.setFactoryCode(mainTask.getFactoryCode());

            // 派工是生产执行策略的冻结边界；后续模板发布、退役均不得改变在制任务。
            executionTemplateService.freezeForDispatch(mainTask, newTask);

            produceTaskCodes.add(newTask.getProduceTaskCode());
            finalTaskArrangeList.add(newTask);
        }

        // 动态闭合区段不能只派绑定节点；全部冻结角色不齐时原永久组及本批派工一起回滚。
        executionTemplateService.validateDispatchBundle(finalTaskArrangeList);
        // 保存派工
        if (!this.saveBatch(finalTaskArrangeList)) {
            throw new CheckedException("实际派工未全部保存");
        }
        // 更新工序任务状态
        if (!produceTaskService.update(new LambdaUpdateWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, tenant).eq(MesProduceTaskEntity::getFactoryCode, factory)
                .eq(MesProduceTaskEntity::getTaskCode, mainTask.getCode())
                .in(MesProduceTaskEntity::getCode, produceTaskCodes)
                .set(MesProduceTaskEntity::getStatus, MesProduceTaskStatusEnum.UN_RECEIVE.name()))) {
            throw new CheckedException("派工工序状态未保存，整笔派工回滚");
        }
        // 补派班次不能把已经开工的原工单退回已派工；终态及锁内旧状态必须仍然一致。
        if (!mesTaskService.update(new LambdaUpdateWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getId, mainTask.getId())
                .eq(MesTaskEntity::getCode, dto.getCode())
                .eq(MesTaskEntity::getStatus, mainTask.getStatus())
                .notIn(MesTaskEntity::getStatus, MesTaskStatusEnum.CLOSE.name(), MesTaskStatusEnum.FINISH.name())
                .set(MesTaskEntity::getStatus, MesTaskStatusEnum.PRODUCT.name().equals(mainTask.getStatus())
                        ? MesTaskStatusEnum.PRODUCT.name() : MesTaskStatusEnum.DISPATCH.name()))) {
            throw new CheckedException("派工工单状态未保存，整笔派工回滚");
        }
    }

    @Override
    public IPage<MesTaskArrangeEntity> padPage(Page<MesTaskArrangeEntity> pageParam, MesTaskArrangeEntity query) {
        if (pageParam == null || query == null) throw new CheckedException("现场任务查询条件不能为空");
        return page(pageParam, padTaskQuery(query.getShiftCode(), query.getWorkshopCode(), query.getWorksiteCodes())
                .eq(StrUtil.isNotBlank(query.getStatus()), "status", query.getStatus()).orderByDesc("create_time"));
    }

    private QueryWrapper<MesTaskArrangeEntity> padTaskQuery(String shiftCode, String workshopCode, String worksiteCode) {
        String factory = actualFactoryAccess.require("read");
        MomUser user = SecurityUtils.getUser();
        if (StrUtil.isBlank(shiftCode) || StrUtil.isBlank(workshopCode) || StrUtil.isBlank(worksiteCode)
                || user == null || StrUtil.isBlank(user.getUsername())) {
            throw new CheckedException("请先选择实际班次、车间和工位，再读取现场任务");
        }
        return new QueryWrapper<MesTaskArrangeEntity>()
                .eq("tenant_id", TenantContextHolder.getTenantId())
                .eq("factory_code", factory)
                .apply("{0} = ANY(string_to_array(worker_code, ','))", user.getUsername())
                .eq("shift_code", shiftCode)
                .eq("workshop_code", workshopCode)
                .apply("{0} = ANY(string_to_array(worksite_codes, ','))", worksiteCode)
                .and(bound -> bound.isNull("actual_worksite_code")
                        .or(blank -> blank.eq("actual_worksite_code", ""))
                        .or(actual -> actual.eq("actual_worksite_code", worksiteCode)));
    }

    /**
     * 根据工位查询状态
     *
     * @param worksiteCode
     * @return
     */
    @Override
    public List<MesTaskArrangeStatusDTO> queryStatusList(String worksiteCode, String shiftCode, String workshopCode) {
        List<MesTaskArrangeStatusDTO> resultList = new ArrayList<>();
        List<MesTaskArrangeEntity> allList = this.list(padTaskQuery(shiftCode, workshopCode, worksiteCode));
        resultList.add(new MesTaskArrangeStatusDTO("", (long) allList.size()));
        if (CollUtil.isNotEmpty(allList)) {
            // 待接收
            long unReceiveCount = allList.stream().filter(item -> MesProduceTaskStatusEnum.UN_RECEIVE.name().equals(item.getStatus())).count();
            resultList.add(new MesTaskArrangeStatusDTO(MesProduceTaskStatusEnum.UN_RECEIVE.name(), unReceiveCount));
            // 已接收
            long receiveCount = allList.stream().filter(item -> MesProduceTaskStatusEnum.RECEIVE.name().equals(item.getStatus())).count();
            resultList.add(new MesTaskArrangeStatusDTO(MesProduceTaskStatusEnum.RECEIVE.name(), receiveCount));
            // 生产中
            long productCount = allList.stream().filter(item -> MesProduceTaskStatusEnum.PRODUCT.name().equals(item.getStatus())).count();
            resultList.add(new MesTaskArrangeStatusDTO(MesProduceTaskStatusEnum.PRODUCT.name(), productCount));
            // 暂停中
            long pauseCount = allList.stream().filter(item -> MesProduceTaskStatusEnum.PAUSE.name().equals(item.getStatus())).count();
            resultList.add(new MesTaskArrangeStatusDTO(MesProduceTaskStatusEnum.PAUSE.name(), pauseCount));
            // 关闭
            long closeCount = allList.stream().filter(item -> MesProduceTaskStatusEnum.CLOSE.name().equals(item.getStatus())).count();
            resultList.add(new MesTaskArrangeStatusDTO(MesProduceTaskStatusEnum.CLOSE.name(), closeCount));
            // 已完成
            long finishCount = allList.stream().filter(item -> MesProduceTaskStatusEnum.FINISH.name().equals(item.getStatus())).count();
            resultList.add(new MesTaskArrangeStatusDTO(MesProduceTaskStatusEnum.FINISH.name(), finishCount));
        }
        return resultList;
    }

    /**
     * 根据工位查询正在加工任务
     *
     * @param shiftCode
     * @return
     */
    @Override
    public MesArrangeTaskVO getCurrentTask(String shiftCode, String workshopCode, String worksiteCode) {
        QueryWrapper<MesTaskArrangeEntity> query = padTaskQuery(shiftCode, workshopCode, worksiteCode);
        String factory = FactoryContextHolder.getFactoryCode();
        MesArrangeTaskVO result = new MesArrangeTaskVO();
        List<MesTaskArrangeEntity> matches = this.list(query
                .eq("actual_worksite_code", worksiteCode).isNotNull("start_time")
                .in("status", List.of(MesProduceTaskStatusEnum.PRODUCT.name(), MesProduceTaskStatusEnum.PAUSE.name()))
                .last("LIMIT 2"));
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() != 1) {
            throw new CheckedException("当前工厂/班次/车间/工位有多份实名在制派工，请先核对归属，禁止猜选任务");
        }
        MesTaskArrangeEntity arrange = matches.get(0);
        BeanUtil.copyProperties(arrange, result);
        result.setShiftNum(arrange.getNoticeNum());
        // PAD使用的对象模式和入口规则必须与报工主事务读取的同一派工快照一致。
        MesExecutionContractDTO contract = executionTemplateService.contract(arrange);
        result.setExecutionContract(contract);
        result.setReportMode(contract.getReportMode());
        MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()));
        if (task == null) {
            throw new CheckedException("当前派工所属工单不存在或不属于当前工厂，请核对来源");
        }
        result.setNoticeNum(task.getNoticeNum());
        result.setProcessCode(task.getProcessRouteCode());
        // 获取当前工序
        MesProduceTaskEntity currentProduceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getFactoryCode, factory)
                .eq(MesProduceTaskEntity::getTaskCode, arrange.getTaskCode())
                .eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode()));
        if (currentProduceTask == null) {
            throw new CheckedException("当前派工所属工序任务不存在或归属不一致，请核对来源");
        }
        MesFrozenExecutionGraph.assertArrangement(currentProduceTask, arrange);
        result.setWorksiteTypes(MesWorksiteTriggerProjection.forExecution(currentProduceTask, arrange).name());
        result.setCompleteNum(currentProduceTask.getCompleteNum());
        // DAG汇合工序的可用前序数量取全部直接前置工序完工数量的最小值。
        List<MesProduceTaskEntity> predecessorTasks = frozenGraph(currentProduceTask).predecessors(currentProduceTask);
        if (!predecessorTasks.isEmpty()) {
            predecessorTasks.stream().map(row -> Optional.ofNullable(row.getCompleteNum()).orElse(BigDecimal.ZERO))
                    .min(BigDecimal::compareTo).ifPresent(result::setLastPassNum);
        }
        result.setArrangeCode(arrange.getCode());

        return result;
    }

    /**
     * 获取当前设备
     *
     * @return
     */
    @Override
    public MesArrangeDeviceVO getCurrentDevice(String shiftCode, String workshopCode, String worksiteCode) {
        List<String> statusList = List.of(MesProduceTaskStatusEnum.RECEIVE.name(),
                MesProduceTaskStatusEnum.PRODUCT.name(), MesProduceTaskStatusEnum.PAUSE.name());
        MomUser currentUser = SecurityUtils.getUser();
        MesTaskArrangeEntity arrange = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .apply("(',' || worker_code || ',') LIKE {0}", "%," + currentUser.getUsername() + ",%")
                .apply(StrUtil.isNotBlank(worksiteCode), "(',' || worksite_codes || ',') LIKE {0}", "%," + worksiteCode + ",%")
                .eq(StrUtil.isNotBlank(workshopCode), MesTaskArrangeEntity::getWorkshopCode, workshopCode)
                .eq(StrUtil.isNotBlank(shiftCode), MesTaskArrangeEntity::getShiftCode, shiftCode)
                .in(MesTaskArrangeEntity::getStatus, statusList)
                .orderByDesc(MesTaskArrangeEntity::getUpdateTime)
                .last("LIMIT 1"), false);
        if (Objects.isNull(arrange)) {
            return null;
        }
        return buildArrangeDevice(arrange, worksiteCode);
    }

    /**
     * 获取当前设备
     *
     * @return
     */
    @Override
    public MesArrangeDeviceVO getRunningDevice(String shiftCode, String workshopCode, String worksiteCode) {
        MesTaskArrangeEntity arrange = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .apply(StrUtil.isNotBlank(worksiteCode), "(',' || worksite_codes || ',') LIKE {0}", "%," + worksiteCode + ",%")
                .eq(StrUtil.isNotBlank(workshopCode), MesTaskArrangeEntity::getWorkshopCode, workshopCode)
                .eq(StrUtil.isNotBlank(shiftCode), MesTaskArrangeEntity::getShiftCode, shiftCode)
                .in(MesTaskArrangeEntity::getStatus, MesProduceTaskStatusEnum.RECEIVE.name(),
                        MesProduceTaskStatusEnum.PRODUCT.name(), MesProduceTaskStatusEnum.PAUSE.name())
                .orderByDesc(MesTaskArrangeEntity::getUpdateTime)
                .last("LIMIT 1"), false);
        return arrange == null ? null : buildArrangeDevice(arrange, worksiteCode);
    }

    private MesArrangeDeviceVO buildArrangeDevice(MesTaskArrangeEntity arrange, String worksiteCode) {
        MesArrangeDeviceVO result = new MesArrangeDeviceVO();
        BeanUtil.copyProperties(arrange, result);
        result.setArrangeCode(arrange.getCode());
        result.setDeviceCode(arrange.getPlannedDeviceCode());
        result.setDeviceName(arrange.getPlannedDeviceName());
        if (StrUtil.isBlank(arrange.getPlannedDeviceCode())) {
            return result;
        }
        MdmDeviceEntity query = new MdmDeviceEntity();
        query.setDeviceCode(arrange.getPlannedDeviceCode());
        R<MdmDeviceEntity> response = deviceService.getDetails(query);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new CheckedException("无法读取APS锁定设备[" + arrange.getPlannedDeviceCode() + "]的实时状态");
        }
        MdmDeviceEntity device = response.getData();
        result.setDeviceName(device.getDeviceName());
        result.setDeviceUsable(device.getDeviceUsable());
        result.setDeviceStatus(Boolean.TRUE.equals(device.getStatus()) ? 1 : 0);
        result.setDeviceState(device.getDeviceState());
        result.setDeviceClassCode(device.getClassCode());
        result.setDeviceClassName(device.getClassName());
        result.setDeviceLocation(device.getLocation());
        result.setDeviceId(device.getId());
        R<List<com.kitex.mom.tpm.entity.TpmDeviceMoldRelaEntity>> relationResponse =
                remoteTpmDeviceService.getListByDeviceCode(device.getDeviceCode());
        if (relationResponse != null && relationResponse.getCode() == 0 && relationResponse.getData() != null) {
            List<String> moldCodes = relationResponse.getData().stream()
                    .map(com.kitex.mom.tpm.entity.TpmDeviceMoldRelaEntity::getMoldCode)
                    .filter(StrUtil::isNotBlank).distinct().toList();
            result.setDeviceMoldRela(moldCodes.isEmpty() ? "0" : "1");
            if (!moldCodes.isEmpty()) {
                R<List<MdmDeviceEntity>> molds = deviceService.getMdmDeviceListByCodes(moldCodes);
                if (molds != null && molds.getCode() == 0 && molds.getData() != null) {
                    result.setMoldList(molds.getData().stream().map(item -> new MesUsableMoldDTO()
                            .setMoldCode(item.getDeviceCode()).setMoldName(item.getDeviceName())).toList());
                }
            }
        }
        MesWorksiteMoldEntity bind = worksiteMoldService.getOne(new LambdaQueryWrapper<MesWorksiteMoldEntity>()
                .eq(MesWorksiteMoldEntity::getWorksiteCode,
                        StrUtil.blankToDefault(worksiteCode, splitCsv(arrange.getWorksiteCodes()).stream().findFirst().orElse(null)))
                .eq(MesWorksiteMoldEntity::getDeviceCode, device.getDeviceCode()), false);
        if (bind != null) {
            result.setModelCode(bind.getMoldCode());
            result.setModelName(bind.getMoldName());
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MdmBomChildListDTO> getBomList(String code) {
        String factory = actualFactoryAccess.require("read");
        MesTaskArrangeEntity arrange = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, code));
        if (arrange == null) {
            return List.of();
        }
        return frozenBomForTask(arrange.getTaskCode(), factory);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MdmBomChildListDTO> getBomListByTaskCode(String code) {
        return frozenBomForTask(code, actualFactoryAccess.require("read"));
    }

    private List<MdmBomChildListDTO> frozenBomForTask(String code, String factory) {
        Long tenant = TenantContextHolder.getTenantId();
        MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant)
                .eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, code));
        if (task == null) {
            throw new CheckedException("当前工厂的工单不存在，无法读取冻结BOM");
        }
        List<MesWarehouseFeedEntity> feedList = feedService.list(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .eq(MesWarehouseFeedEntity::getTenantId, tenant)
                .eq(MesWarehouseFeedEntity::getFactoryCode, factory)
                .eq(MesWarehouseFeedEntity::getTaskCode, code));
        return MesFrozenBomProjection.project(task.getBomSnapshot(), task.getNoticeNum(), feedList);
    }

    /**
     * 暂停
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pause(Long id) {
        MesTaskArrangeEntity task = lockedForStateChange(id);
        terminalExecutionGuard.assertUnoccupied(task);
        assertAssignedWorker(task);
        pauseArrange(task, "MANUAL_PAUSE");
    }

    /**
     * 移动原号人工暂停；安全控制暂停继续走独立入口，不受未决占用阻挡。
     */
    @Override
    @org.springframework.security.access.prepost.PreAuthorize("@pms.hasPermission('mesProduceTask:pause')")
    @Transactional(rollbackFor = Exception.class)
    public TpmDevicePauseReceipt pauseFromTerminal(Long id, String requestId) {
        MesTaskArrangeEntity task = lockedForStateChange(id);
        terminalExecutionGuard.assertUnoccupied(task);
        assertAssignedWorker(task);
        return pauseArrange(task, "MOBILE_MANUAL_PAUSE", requestId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pauseByAndon(String arrangeCode) {
        pauseByAndon(arrangeCode, java.util.UUID.randomUUID().toString());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TpmDevicePauseReceipt pauseByAndon(String arrangeCode, String requestId) {
        return pauseByControl(arrangeCode, requestId, "安灯联动暂停必须关联派工单", "ANDON_PAUSE");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TpmDevicePauseReceipt pauseByChangePoint(String arrangeCode, String requestId) {
        return pauseByControl(arrangeCode, requestId, "变化点联动暂停必须关联派工单", "CHANGE_POINT_PAUSE");
    }

    private TpmDevicePauseReceipt pauseByControl(String arrangeCode, String requestId,
                                                 String missingMessage, String pauseReason) {
        if (StrUtil.isBlank(arrangeCode)) {
            throw new CheckedException(missingMessage);
        }
        MesTaskArrangeEntity before = executionTaskLock.arrange(arrangeCode);
        MesTaskArrangeEntity task = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, before.getId())
                .eq(MesTaskArrangeEntity::getTenantId, before.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, before.getFactoryCode())
                .last("FOR UPDATE"));
        executionTaskLock.assertArrange(before, task);
        if (MesProduceTaskStatusEnum.PAUSE.name().equals(task.getStatus())) {
            return null;
        }
        return pauseArrange(task, pauseReason, requestId);
    }

    private void pauseArrange(MesTaskArrangeEntity task, String pauseReason) {
        pauseArrange(task, pauseReason, java.util.UUID.randomUUID().toString());
    }

    private TpmDevicePauseReceipt pauseArrange(MesTaskArrangeEntity task, String pauseReason, String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}"))
            throw new CheckedException("暂停尝试请求号无效");
        TpmDevicePauseReceipt receipt = null;
        if (com.kitex.common.data.tenant.TenantContextHolder.getTenantId() == null
                || com.kitex.common.data.tenant.TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(com.kitex.common.data.orgFilter.FactoryContextHolder.getFactorySkip())
                || StrUtil.isBlank(task.getFactoryCode())
                || !Objects.equals(task.getTenantId(), com.kitex.common.data.tenant.TenantContextHolder.getTenantId())
                || !Objects.equals(task.getFactoryCode(), com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode())) {
            throw new CheckedException("暂停须在派工的实际租户/工厂内执行，不允许跳过隔离");
        }
        if (!MesProduceTaskStatusEnum.PRODUCT.name().equals(task.getStatus())) {
            throw new CheckedException("只有生产中的任务可以暂停");
        }
        if (StrUtil.isNotBlank(task.getPlannedDeviceCode())) {
            long otherRunning = this.count(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .ne(MesTaskArrangeEntity::getId, task.getId())
                    .eq(MesTaskArrangeEntity::getFactoryCode, task.getFactoryCode())
                    .eq(MesTaskArrangeEntity::getPlannedDeviceCode, task.getPlannedDeviceCode())
                    .eq(MesTaskArrangeEntity::getStatus, MesProduceTaskStatusEnum.PRODUCT.name()));
            if (otherRunning == 0) {
                String worksite = task.getActualWorksiteCode();
                if (StrUtil.isBlank(worksite)) {
                    var configured = splitCsv(task.getWorksiteCodes()).stream().distinct().toList();
                    if (configured.size() != 1)
                        throw new CheckedException("暂停设备缺少唯一实际工位，不能取多个候选中的第一项");
                    worksite = configured.get(0);
                }
                var pauseBindings = worksiteMoldService.list(
                        new LambdaQueryWrapper<MesWorksiteMoldEntity>()
                                .eq(MesWorksiteMoldEntity::getWorksiteCode, worksite)
                                .eq(MesWorksiteMoldEntity::getFactoryCode, task.getFactoryCode())
                                .eq(MesWorksiteMoldEntity::getDeviceCode, task.getPlannedDeviceCode()).last("LIMIT 2"));
                if (pauseBindings.size() > 1) throw new CheckedException("工位设备模具绑定重复，禁止猜测暂停对象");
                MesWorksiteMoldEntity bind = pauseBindings.isEmpty() ? null : pauseBindings.get(0);
                TpmDevicePauseCommand command = new TpmDevicePauseCommand();
                command.setRequestId(requestId);
                command.setDeviceCode(task.getPlannedDeviceCode());
                command.setMoldCode(bind == null ? null : bind.getMoldCode());
                command.setReason(pauseReason + "；派工ID=" + task.getId() + "；只转换业务状态，不代表PLC停机");
                terminalExecutionGuard.beforeProductionRemoteWrite();
                var pauseResult = remoteTpmDeviceService.devicePauseReceipt(command);
                receipt = pauseResult == null ? null : pauseResult.getData();
                if (pauseResult == null || !pauseResult.isOk() || receipt == null || StrUtil.isBlank(receipt.getId())
                        || !Objects.equals(requestId, receipt.getRequestId())
                        || !"PAUSE".equals(receipt.getAction())
                        || !Objects.equals(task.getTenantId(), receipt.getTenantId()) || !Objects.equals(task.getFactoryCode(), receipt.getFactoryCode())
                        || !Objects.equals(task.getPlannedDeviceCode(), receipt.getDeviceCode())
                        || !Objects.equals(StrUtil.trimToNull(command.getMoldCode()), receipt.getMoldCode())
                        || SecurityUtils.getUser() == null || !Objects.equals(SecurityUtils.getUser().getId(), receipt.getOperatorId())
                        || StrUtil.isBlank(receipt.getEvidenceJson())) {
                    throw new CheckedException("任务暂停没有取得匹配的TPM/MDM状态回执；结果不明须按尝试号核对");
                }
            }
        }
        LocalDateTime pauseTime = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        closeExecutionSegment(task, pauseReason, "CLOSED", pauseTime);
        workpieceTimeService.pauseForArrange(task, pauseTime, pauseReason);
        task.setStatus(MesProduceTaskStatusEnum.PAUSE.name());
        if (!this.updateById(task)) throw new CheckedException("派工暂停状态保存失败，须核对远端回执后重试");
        MesProduceTaskEntity produceTask = produceTaskService.getOne(
                new LambdaQueryWrapper<MesProduceTaskEntity>()
                        .eq(MesProduceTaskEntity::getCode, task.getProduceTaskCode())
                        .eq(MesProduceTaskEntity::getFactoryCode, task.getFactoryCode()).last("FOR UPDATE"), false);
        if (produceTask == null || !Set.of(MesProduceTaskStatusEnum.PRODUCT.name(),
                MesProduceTaskStatusEnum.PAUSE.name()).contains(produceTask.getStatus())) {
            throw new CheckedException("工序任务与派工生产状态不一致，暂停结果不确定，须按尝试号核对");
        }
        long otherRunningArranges = this.count(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .ne(MesTaskArrangeEntity::getId, task.getId())
                .eq(MesTaskArrangeEntity::getProduceTaskCode, task.getProduceTaskCode())
                .eq(MesTaskArrangeEntity::getFactoryCode, task.getFactoryCode())
                .eq(MesTaskArrangeEntity::getStatus, MesProduceTaskStatusEnum.PRODUCT.name()));
        produceTask.setStatus(otherRunningArranges == 0
                ? MesProduceTaskStatusEnum.PAUSE.name() : MesProduceTaskStatusEnum.PRODUCT.name());
        if (!produceTaskService.updateById(produceTask)) {
            throw new CheckedException("工序任务暂停状态保存失败，须按尝试号核对远端回执");
        }
        return receipt;
    }

    /**
     * 按冻结APS完整集合领用工装，重新验证实时工艺能力；整组本地事实必须全部成功。
     * 同一派工已有CHECKED_IN/IN_USE记录时不新增，未使用且已释放的工装可重新领用。
     *
     * @param dto 实际派工主键与完整扫码工装集合
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkInToolings(MesToolingCheckInDTO dto) {
        if (dto == null || dto.getArrangeId() == null) {
            throw new CheckedException("派工任务不能为空");
        }
        MesTaskArrangeEntity task = lockedForStateChange(dto.getArrangeId());
        terminalExecutionGuard.assertUnoccupied(task);
        assertAssignedWorker(task);
        if (!Set.of(MesProduceTaskStatusEnum.UN_RECEIVE.name(), MesProduceTaskStatusEnum.RECEIVE.name(),
                MesProduceTaskStatusEnum.PAUSE.name()).contains(task.getStatus())) {
            throw new CheckedException("只有待接收、已接收或暂停任务可以领用工装");
        }
        LinkedHashSet<String> planned = new LinkedHashSet<>(splitCsv(task.getPlannedToolingCodes()));
        LinkedHashSet<String> scanned = Optional.ofNullable(dto.getToolingCodes()).orElse(List.of()).stream()
                .filter(StrUtil::isNotBlank).map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!planned.equals(scanned)) {
            throw new CheckedException("扫码工装必须与APS锁定工装完全一致，计划["
                    + String.join(",", planned) + "]，实扫[" + String.join(",", scanned) + "]");
        }
        if (planned.isEmpty()) {
            return;
        }
        MesProduceTaskEntity produceTask = frozenParent(task);
        if (produceTask == null || StrUtil.isBlank(produceTask.getProcessRouteCode())) {
            throw new CheckedException("工序任务缺少工艺路线，无法复核工装");
        }
        LocalDateTime checkStart = LocalDateTime.now();
        LocalDateTime checkEnd = task.getPlannedEndTime() != null && task.getPlannedEndTime().isAfter(checkStart)
                ? task.getPlannedEndTime() : checkStart.plusMinutes(1);
        MdmToolingAvailabilityQueryDTO query = new MdmToolingAvailabilityQueryDTO();
        query.setRouteCodes(List.of(produceTask.getProcessRouteCode()));
        query.setProcessCodes(List.of(task.getRouteCode()));
        if (StrUtil.isNotBlank(produceTask.getSourceRouteNodeCode())) {
            query.setRouteNodeCodes(List.of(produceTask.getSourceRouteNodeCode()));
        }
        query.setToolingCodes(new ArrayList<>(planned));
        query.setStartTime(checkStart);
        query.setEndTime(checkEnd);
        R<List<MdmToolingCandidateDTO>> response = remoteMdmToolingService.availability(query);
        List<MdmToolingCandidateDTO> candidates = response == null ? null : response.getData();
        if (response == null || !response.isOk() || CollUtil.isEmpty(candidates)) {
            throw new CheckedException("未取得工装实时能力数据，禁止领用");
        }
        // 不能把另一次同名工序的候选当作本次领用资格；旧无节点目录也必须唯一。
        Set<String> candidateNodes = new HashSet<>();
        for (MdmToolingCandidateDTO candidate : candidates) {
            var requirement = candidate == null ? null : candidate.getRequirementSnapshot();
            if (candidate == null || requirement == null
                    || !Objects.equals(produceTask.getProcessRouteCode(), candidate.getRouteCode())
                    || !Objects.equals(produceTask.getRouteCode(), candidate.getProcessCode())
                    || !Objects.equals(candidate.getRequirementId(), requirement.getId())
                    || !Objects.equals(task.getTenantId(), requirement.getTenantId())
                    || !Objects.equals(task.getFactoryCode(), requirement.getFactoryCode())
                    || !Objects.equals(candidate.getRouteCode(), requirement.getRouteCode())
                    || !Objects.equals(candidate.getProcessCode(), requirement.getProcessCode())
                    || !Objects.equals(candidate.getRouteNodeCode(), requirement.getRouteNodeCode())
                    || StrUtil.isNotBlank(produceTask.getSourceRouteNodeCode())
                    && !Objects.equals(produceTask.getSourceRouteNodeCode(), candidate.getRouteNodeCode())) {
                throw new CheckedException("工装候选与原实际工序的组织、路线或节点不一致");
            }
            candidateNodes.add(candidate.getRouteNodeCode());
        }
        if (candidateNodes.size() != 1) throw new CheckedException("旧工装主工序不能唯一对应节点，禁止猜选");
        Map<Long, List<MdmToolingCandidateDTO>> byRequirement = candidates.stream()
                .filter(item -> item.getRequirementId() != null)
                .collect(Collectors.groupingBy(MdmToolingCandidateDTO::getRequirementId));
        String deviceCode = task.getPlannedDeviceCode();
        for (Map.Entry<Long, List<MdmToolingCandidateDTO>> requirementEntry : byRequirement.entrySet()) {
            MdmToolingCandidateDTO first = requirementEntry.getValue().get(0);
            if (first.getRequirementSnapshot() == null || Boolean.FALSE.equals(first.getRequirementSnapshot().getMandatory())) {
                continue;
            }
            int required = Optional.ofNullable(first.getRequirementSnapshot().getRequiredQuantity()).orElse(1);
            long qualifiedCount = requirementEntry.getValue().stream()
                    .filter(item -> planned.contains(item.getToolingCode()))
                    .filter(item -> Boolean.TRUE.equals(item.getQualified()))
                    .filter(item -> !Boolean.TRUE.equals(first.getRequirementSnapshot().getDeviceCompatibilityRequired())
                            || StrUtil.isNotBlank(deviceCode) && item.getCompatibleDeviceCodes().contains(deviceCode))
                    .filter(item -> Optional.ofNullable(item.getUnavailableWindows()).orElse(List.of()).stream().noneMatch(window ->
                            window.getStartTime().isBefore(checkEnd) && window.getEndTime().isAfter(checkStart)))
                    .map(MdmToolingCandidateDTO::getToolingCode).filter(Objects::nonNull).distinct().count();
            if (qualifiedCount < required) {
                throw new CheckedException("工装要求[" + first.getRequirementSnapshot().getRequirementSeq()
                        + "]实时状态、寿命、校准、设备兼容或可用窗口复核失败");
            }
        }
        Map<String, MdmToolingCandidateDTO> selectedCandidates = candidates.stream()
                .filter(item -> planned.contains(item.getToolingCode()))
                .filter(item -> Boolean.TRUE.equals(item.getQualified()))
                .collect(Collectors.toMap(MdmToolingCandidateDTO::getToolingCode, item -> item, (a, b) -> a));
        if (!selectedCandidates.keySet().containsAll(planned)) {
            throw new CheckedException("部分APS锁定工装已失效或不再满足工艺要求，禁止领用");
        }
        Map<String, BigDecimal> lifeUsage = parseLifeUsage(task.getToolingLifeUsageSnapshot());
        Map<String, String> names = zipCsv(task.getPlannedToolingCodes(), task.getPlannedToolingNames());
        MomUser currentUser = SecurityUtils.getUser();
        for (String toolingCode : planned) {
            MesToolingExecutionEntity execution = toolingExecutionMapper.selectOne(
                    new LambdaQueryWrapper<MesToolingExecutionEntity>()
                            .eq(MesToolingExecutionEntity::getTenantId, task.getTenantId())
                            .eq(MesToolingExecutionEntity::getFactoryCode, task.getFactoryCode())
                            .eq(MesToolingExecutionEntity::getArrangeCode, task.getCode())
                            .eq(MesToolingExecutionEntity::getToolingCode, toolingCode));
            if (execution == null) {
                MdmToolingCandidateDTO candidate = selectedCandidates.get(toolingCode);
                execution = new MesToolingExecutionEntity();
                execution.setTenantId(task.getTenantId());
                execution.setFactoryCode(task.getFactoryCode());
                execution.setArrangeCode(task.getCode());
                execution.setProduceTaskCode(task.getProduceTaskCode());
                execution.setTaskCode(task.getTaskCode());
                execution.setToolingCode(toolingCode);
                execution.setToolingName(names.getOrDefault(toolingCode, candidate.getToolingName()));
                execution.setToolingType(candidate.getToolingType());
                execution.setStatus("CHECKED_IN");
                execution.setPlannedLifeUsage(lifeUsage.get(toolingCode));
                execution.setCheckInWorkerCode(currentUser.getUsername());
                execution.setCheckInWorkerName(currentUser.getName());
                execution.setCheckInTime(LocalDateTime.now());
                if (toolingExecutionMapper.insert(execution) != 1) {
                    throw new CheckedException("工装领用事实保存失败，完整集合已本地回滚");
                }
            } else if ("RELEASED".equals(execution.getStatus())
                    && StrUtil.isBlank(execution.getLifeConsumptionRequestId())) {
                // 显式置空旧使用/释放时间，不能由updateById的忽略null策略保留旧事实。
                int updated = toolingExecutionMapper.update(null, new LambdaUpdateWrapper<MesToolingExecutionEntity>()
                        .eq(MesToolingExecutionEntity::getId, execution.getId())
                        .eq(MesToolingExecutionEntity::getTenantId, task.getTenantId())
                        .eq(MesToolingExecutionEntity::getFactoryCode, task.getFactoryCode())
                        .eq(MesToolingExecutionEntity::getStatus, "RELEASED")
                        .set(MesToolingExecutionEntity::getStatus, "CHECKED_IN")
                        .set(MesToolingExecutionEntity::getCheckInWorkerCode, currentUser.getUsername())
                        .set(MesToolingExecutionEntity::getCheckInWorkerName, currentUser.getName())
                        .set(MesToolingExecutionEntity::getCheckInTime, LocalDateTime.now())
                        .set(MesToolingExecutionEntity::getStartTime, null)
                        .set(MesToolingExecutionEntity::getReleaseTime, null)
                        .set(MesToolingExecutionEntity::getPlannedLifeUsage, lifeUsage.get(toolingCode)));
                if (updated != 1) {
                    throw new CheckedException("工装重新领用事实保存失败，完整集合已本地回滚");
                }
            } else if (!Set.of("CHECKED_IN", "IN_USE").contains(execution.getStatus())) {
                throw new CheckedException("工装[" + toolingCode + "]已结算或执行记录异常，不能重复领用");
            }
        }
    }

    @Override
    public List<MesToolingExecutionEntity> listToolingExecutions(Long arrangeId) {
        MesTaskArrangeEntity task = arrangeId == null ? null : this.getById(arrangeId);
        if (task == null) {
            throw new CheckedException("加工任务不存在");
        }
        return toolingExecutionMapper.selectList(new LambdaQueryWrapper<MesToolingExecutionEntity>()
                .eq(MesToolingExecutionEntity::getArrangeCode, task.getCode())
                .orderByAsc(MesToolingExecutionEntity::getToolingCode));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void receive(Long id, String worksiteCode) {
        MesTaskArrangeEntity task = lockedForStateChange(id);
        terminalExecutionGuard.assertUnoccupied(task);
        if (!MesProduceTaskStatusEnum.UN_RECEIVE.name().equals(task.getStatus())) {
            throw new RuntimeException(MesProduceTaskStatusEnum.CLOSE.name().equals(task.getStatus())
                    ? "派工任务已关闭，必须重新派工，不能恢复原派工"
                    : "加工任务已被接收");
        }
        assertAssignedWorker(task);
        dispatchReturnService.assertExecutionAllowed(task);
        if (StrUtil.isBlank(worksiteCode) || !splitCsv(task.getWorksiteCodes()).contains(worksiteCode)) {
            throw new CheckedException("当前工位不属于该派工任务，不能接收");
        }
        assertToolingsCheckedIn(task);

        boolean existTask = this.hasStartTask(worksiteCode, task.getWorkshopCode(), task.getShiftCode(), null);
        if (existTask) {
            throw new RuntimeException("同一工位，只能同时开始一个加工任务");
        }
        MesDeviceGateDecision deviceDecision = deviceGateService.check(
                MesDeviceGateStageEnum.RECEIVE, task, null, worksiteCode);
        if (!Boolean.TRUE.equals(deviceDecision.getAllowed())) {
            throw new CheckedException("设备运行门阻断接收：" + deviceDecision.getMessage());
        }
        // 接收沿用原工单冻结的模具要求；同主工序多次执行不能取首条，也不能改读今日工程配置。
        List<MesTaskEntity> owners = mesTaskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, task.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, task.getFactoryCode())
                .eq(MesTaskEntity::getCode, task.getTaskCode()).last("LIMIT 2"));
        if (owners.size() != 1) {
            throw new CheckedException("派工未唯一归属原租户工厂的生产工单，不能核验冻结模具要求");
        }
        ProcessProcedureDTO progress = MesTaskUtils.getCurrentProcess(
                owners.get(0).getProcessRouteSnapshot(), task.getRouteCode(), task.getSourceRouteNodeCode());

        List<ProcessProcedureDTO.ProcessProcedureMoldDTO> moldList = progress.getMdmProcessProcedureMoldList();
        if (CollUtil.isNotEmpty(moldList) && StrUtil.isNotEmpty(task.getPlannedDeviceCode())) {
            // 查询工作中心-设备-模具绑定表 拿到唯一的绑定模具
            MesWorksiteMoldEntity worksiteMold = worksiteMoldService.getOne(new LambdaQueryWrapper<MesWorksiteMoldEntity>()
                    .eq(MesWorksiteMoldEntity::getTenantId, task.getTenantId())
                    .eq(MesWorksiteMoldEntity::getFactoryCode, task.getFactoryCode())
                    .eq(MesWorksiteMoldEntity::getWorksiteCode, worksiteCode)
                    .eq(MesWorksiteMoldEntity::getDeviceCode, task.getPlannedDeviceCode()));
            List<String> moldCodeList = moldList.stream().map(ProcessProcedureDTO.ProcessProcedureMoldDTO::getMoldCode).toList();
            // 如果绑定的模具在moldList中 则不需要换模
            if (worksiteMold == null || !moldCodeList.contains(worksiteMold.getMoldCode())) {
                throw new RuntimeException("请进行换模后再次接收任务");
            }
        }

        task.setStatus(MesProduceTaskStatusEnum.RECEIVE.name());
        task.setActualWorksiteCode(worksiteCode);
        if (!this.updateById(task)) throw new CheckedException("派工接收事实保存失败");
    }

    /**
     * 任务开工
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void start(Long id) {
        startInternal(id, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startWithMaterialSources(Long id, List<Long> feedIds) {
        if (feedIds == null || feedIds.isEmpty() || feedIds.size() > 100
                || feedIds.stream().anyMatch(value -> value == null || value <= 0)
                || new HashSet<>(feedIds).size() != feedIds.size()) {
            throw new CheckedException("开工绑定须提供1—100个不同的有效投料来源");
        }
        startInternal(id, List.copyOf(feedIds));
    }

    private void startInternal(Long id, List<Long> selectedFeedIds) {
        MesTaskArrangeEntity task = lockedForStateChange(id);
        terminalExecutionGuard.assertUnoccupied(task);
        if (!(task.getStatus().equals(MesProduceTaskStatusEnum.RECEIVE.name())
                || task.getStatus().equals(MesProduceTaskStatusEnum.PAUSE.name()))) {
            throw new RuntimeException("只有已接收、已暂停任务才能开工");
        }
        boolean resuming = MesProduceTaskStatusEnum.PAUSE.name().equals(task.getStatus());
        assertAssignedWorker(task);
        dispatchReturnService.assertExecutionAllowed(task);
        startContractGuard.assertToolingBound(task);
        assertToolingsCheckedIn(task);
        sopExecutionService.assertStartAllowed(task);
        andonProductionReleaseService.assertAllowed(task, "开工/复工");
        repairProductionReleaseService.assertAllowed(task, "开工/复工");
        MesProduceTaskEntity produceTask = frozenParent(task, true);
        MesTaskEntity mainTask = mainTaskForArrange(task, true);
        if (terminalParent(produceTask.getStatus()) || terminalParent(mainTask.getStatus())) {
            throw new CheckedException("父工序或工单已经关闭/完工，派工不能重新开工");
        }
        assertPredecessorsFinished(produceTask);
        changePointHandleService.assertStartAllowed(task);

        startContractGuard.assertDeviceBound(task);
        MesDeviceGateDecision deviceDecision = deviceGateService.check(
                MesDeviceGateStageEnum.START, task, null, task.getActualWorksiteCode());
        if (!Boolean.TRUE.equals(deviceDecision.getAllowed())) {
            throw new CheckedException("设备运行门阻断开工：" + deviceDecision.getMessage());
        }

        boolean existTask = this.hasStartTask(StrUtil.blankToDefault(task.getActualWorksiteCode(), task.getWorksiteCodes()),
                task.getWorkshopCode(), task.getShiftCode(), task.getId());
        if (existTask) {
            throw new RuntimeException("同一工位，只能同时开始一个加工任务");
        }
        materialStartGate.assertStart(task, selectedFeedIds);

        // TODO 自动/手动 区别判断处理
        String type = task.getWorksiteTypes();
        if (WorksiteTypeEnum.AUTO.name().equals(type)) {

        }
        task.setStatus(MesProduceTaskStatusEnum.PRODUCT.name());
        // 同一次开工的派工、工序和首段使用同一数据库微秒精度时刻，避免分别取now产生假缺口。
        LocalDateTime executionTime = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        LocalDateTime startDate = task.getStartTime();
        if (Objects.isNull(startDate)) {
            task.setStartTime(executionTime);
        }

        MesTaskExecutionSegmentEntity resumedSegment = openExecutionSegment(task, executionTime);
        if (resuming) {
            workpieceTimeService.resumeForArrange(task, executionTime);
        }

        toolingExecutionMapper.update(null, new LambdaUpdateWrapper<MesToolingExecutionEntity>()
                .eq(MesToolingExecutionEntity::getArrangeCode, task.getCode())
                .eq(MesToolingExecutionEntity::getStatus, "CHECKED_IN")
                .set(MesToolingExecutionEntity::getStatus, "IN_USE")
                .set(MesToolingExecutionEntity::getStartTime, task.getStartTime()));

        if (!this.updateById(task)) throw new CheckedException("派工开工事实保存失败");


        // 更新工序任务
        produceTask.setStatus(MesProduceTaskStatusEnum.PRODUCT.name());
        if (Objects.isNull(produceTask.getStartTime())) {
            produceTask.setStartTime(task.getStartTime());
        }
        if (!produceTaskService.updateById(produceTask)) throw new CheckedException("工序开工事实保存失败");
        // 真实开工/复工与派工、工序同事务推进原主工单；不补写历史开始时间或数量。
        if (!MesTaskStatusEnum.PRODUCT.name().equals(mainTask.getStatus())
                && !mesTaskService.update(new LambdaUpdateWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getId, mainTask.getId())
                .eq(MesTaskEntity::getTenantId, task.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, task.getFactoryCode())
                .eq(MesTaskEntity::getCode, task.getTaskCode())
                .eq(MesTaskEntity::getStatus, mainTask.getStatus())
                .notIn(MesTaskEntity::getStatus, MesTaskStatusEnum.CLOSE.name(), MesTaskStatusEnum.FINISH.name())
                .set(MesTaskEntity::getStatus, MesTaskStatusEnum.PRODUCT.name()))) {
            throw new CheckedException("生产工单开工状态保存失败，整笔开工回滚");
        }
        repairResumeEventService.recordActualResume(task, resumedSegment);
    }

    /**
     * 并行分支可独立执行，汇合工序必须等待其全部直接前置工序完成。
     */
    private void assertPredecessorsFinished(MesProduceTaskEntity current) {
        MesFrozenExecutionGraph graph = frozenGraph(current);
        LocalDateTime now = LocalDateTime.now();
        List<String> blocking = graph.predecessors(current).stream()
                .filter(predecessor -> !predecessorReleased(predecessor, now))
                .map(predecessor -> graph.key(predecessor) + "/" + predecessor.getRouteName()
                        + "(" + predecessor.getStatus() + ",已完成"
                        + Optional.ofNullable(predecessor.getCompleteNum()).orElse(BigDecimal.ZERO) + ")")
                .toList();
        if (!blocking.isEmpty()) {
            throw new CheckedException("前置工序尚未全部完成，当前工序不能开工：" + String.join("、", blocking));
        }
    }

    private boolean predecessorReleased(MesProduceTaskEntity predecessor, LocalDateTime now) {
        return predecessor.getTransferReleasedTime() != null
                && !predecessor.getTransferReleasedTime().isAfter(now);
    }

    private void assertAssignedWorker(MesTaskArrangeEntity task) {
        MomUser currentUser = SecurityUtils.getUser();
        if (currentUser == null || StrUtil.isBlank(currentUser.getUsername())
                || !splitCsv(task.getWorkerCode()).contains(currentUser.getUsername())) {
            throw new CheckedException("当前用户不是派工任务指定的执行员工，不能接收或开工");
        }
    }

    private List<String> splitCsv(String value) {
        if (StrUtil.isBlank(value)) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }

    private Map<String, String> zipCsv(String codesValue, String namesValue) {
        List<String> codes = splitCsv(codesValue);
        List<String> names = splitCsv(namesValue);
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            result.put(codes.get(i), i < names.size() ? names.get(i) : codes.get(i));
        }
        return result;
    }

    private Map<String, BigDecimal> parseLifeUsage(String snapshot) {
        if (StrUtil.isBlank(snapshot)) {
            return Collections.emptyMap();
        }
        try {
            JSONObject object = JSONUtil.parseObj(snapshot);
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (String key : object.keySet()) {
                BigDecimal value = object.getBigDecimal(key);
                if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("invalid tooling usage");
                }
                result.put(key, value);
            }
            return result;
        } catch (Exception ex) {
            throw new CheckedException("APS工装寿命预占快照损坏，禁止继续执行");
        }
    }

    private void assertToolingsCheckedIn(MesTaskArrangeEntity task) {
        Set<String> planned = new LinkedHashSet<>(splitCsv(task.getPlannedToolingCodes()));
        if (planned.isEmpty()) {
            return;
        }
        List<MesToolingExecutionEntity> records = toolingExecutionMapper.selectList(
                new LambdaQueryWrapper<MesToolingExecutionEntity>()
                        .eq(MesToolingExecutionEntity::getArrangeCode, task.getCode())
                        .in(MesToolingExecutionEntity::getStatus, List.of("CHECKED_IN", "IN_USE")));
        Set<String> checked = records.stream().map(MesToolingExecutionEntity::getToolingCode)
                .collect(Collectors.toSet());
        if (!checked.equals(planned)) {
            throw new CheckedException("APS锁定工装尚未全部扫码领用，计划[" + String.join(",", planned)
                    + "]，已领用[" + String.join(",", checked) + "]");
        }
    }

    private void consumeToolingLife(MesTaskArrangeEntity task) {
        Set<String> planned = new LinkedHashSet<>(splitCsv(task.getPlannedToolingCodes()));
        if (planned.isEmpty()) {
            return;
        }
        assertToolingsCheckedIn(task);
        Map<String, BigDecimal> lifeUsage = parseLifeUsage(task.getToolingLifeUsageSnapshot());
        if (!lifeUsage.keySet().equals(planned)) {
            throw new CheckedException("APS工装寿命预占与锁定工装不一致，禁止完工扣减");
        }
        String requestId = "MES_FINISH:" + task.getCode();
        MdmToolingUsageRequestDTO request = new MdmToolingUsageRequestDTO();
        request.setRequestId(requestId);
        request.setBusinessType("MES_TASK_FINISH");
        request.setBusinessCode(task.getCode());
        request.setRemark("MES派工任务完工自动扣减工装寿命");
        request.setLines(lifeUsage.entrySet().stream().map(entry -> {
            MdmToolingUsageLineDTO line = new MdmToolingUsageLineDTO();
            line.setToolingCode(entry.getKey());
            line.setUsage(entry.getValue());
            return line;
        }).toList());
        terminalExecutionGuard.beforeProductionRemoteWrite();
        R<?> response = remoteMdmToolingService.consumeLife(request);
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("MDM未确认工装寿命扣减，禁止完工");
        }
        LocalDateTime releaseTime = LocalDateTime.now();
        int released = toolingExecutionMapper.update(null, new LambdaUpdateWrapper<MesToolingExecutionEntity>()
                .eq(MesToolingExecutionEntity::getArrangeCode, task.getCode())
                .in(MesToolingExecutionEntity::getToolingCode, planned)
                .in(MesToolingExecutionEntity::getStatus, List.of("CHECKED_IN", "IN_USE"))
                .set(MesToolingExecutionEntity::getStatus, "RELEASED")
                .setSql("actual_life_usage = planned_life_usage")
                .set(MesToolingExecutionEntity::getReleaseTime, releaseTime)
                .set(MesToolingExecutionEntity::getLifeConsumptionRequestId, requestId));
        if (released != planned.size()) {
            throw new CheckedException("工装寿命已幂等登记，但本地释放记录数量不一致，请修复执行记录后重试完工");
        }
    }

    /**
     * 状态变更先按真实归属取工单业务锁，再锁派工并重核；与绑定出口核对/报工共序。
     * 接收也须读取锁内状态，禁止旧UN_RECEIVE实体覆盖已关闭事实。
     */
    private MesTaskArrangeEntity lockedForStateChange(Long id) {
        if (id == null) throw new CheckedException("请选择精确派工任务");
        MesTaskArrangeEntity before = executionTaskLock.arrange(id);
        MesTaskArrangeEntity locked = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, id)
                .eq(MesTaskArrangeEntity::getTenantId, before.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, before.getFactoryCode()).last("FOR UPDATE"));
        executionTaskLock.assertArrange(before, locked);
        frozenParent(locked);
        return locked;
    }

    /**
     * 必须读取同一原工单的完整实际任务集，不能先按主工序过滤掉歧义。
     */
    private MesFrozenExecutionGraph frozenGraph(MesProduceTaskEntity current) {
        return MesFrozenExecutionGraph.of(produceTaskService.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, current.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, current.getFactoryCode())
                .eq(MesProduceTaskEntity::getTaskCode, current.getTaskCode())));
    }

    /**
     * 原派工归属及执行图是出生合同，状态变更不能借同码其他任务。
     */
    private MesProduceTaskEntity frozenParent(MesTaskArrangeEntity arrange) {
        return frozenParent(arrange, false);
    }

    private MesProduceTaskEntity frozenParent(MesTaskArrangeEntity arrange, boolean lock) {
        List<MesProduceTaskEntity> matches = produceTaskService.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, arrange.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesProduceTaskEntity::getTaskCode, arrange.getTaskCode())
                .eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode()).last(lock ? "LIMIT 2 FOR UPDATE" : "LIMIT 2"));
        if (matches.size() != 1) throw new CheckedException("派工未唯一归属原实际工序任务");
        MesProduceTaskEntity source = matches.get(0);
        MesFrozenExecutionGraph.assertArrangement(source, arrange);
        return source;
    }

    private String resolveSelectedValues(String sourceCodes, String sourceValues, List<String> selectedCodes) {
        List<String> codes = splitCsv(sourceCodes);
        List<String> values = splitCsv(sourceValues);
        List<String> selectedValues = new ArrayList<>();
        for (String selectedCode : selectedCodes) {
            int index = codes.indexOf(selectedCode);
            selectedValues.add(index >= 0 && index < values.size() ? values.get(index) : selectedCode);
        }
        return String.join(",", selectedValues);
    }

    /**
     * 关闭
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void close(Long id) {
        MesTaskArrangeEntity task = lockedForStateChange(id);
        terminalExecutionGuard.assertUnoccupied(task);
        if (MesProduceTaskStatusEnum.RECEIVE.name().equals(task.getStatus())) {
            throw new CheckedException("已接收未开工任务必须走受控退派申请，不能直接关闭");
        }
        assertAssignedWorker(task);
        if (MesProduceTaskStatusEnum.CLOSE.name().equals(task.getStatus())) return;
        if (task.getStatus() == null || !Set.of("WAIT", "UN_RECEIVE", "PRODUCT", "PAUSE").contains(task.getStatus())) {
            throw new CheckedException("当前派工状态不能关闭，已完成事实不得改成关闭");
        }
        MesProduceTaskEntity produceTask = frozenParent(task);
        MesTaskEntity mainTask = mainTaskForArrange(task);
        long inUseToolings = toolingExecutionMapper.selectCount(new LambdaQueryWrapper<MesToolingExecutionEntity>()
                .eq(MesToolingExecutionEntity::getArrangeCode, task.getCode())
                .eq(MesToolingExecutionEntity::getStatus, "IN_USE"));
        if (inUseToolings > 0) {
            throw new CheckedException("加工中工装尚未进行完工寿命结算，不能直接关闭任务");
        }
        toolingExecutionMapper.update(null, new LambdaUpdateWrapper<MesToolingExecutionEntity>()
                .eq(MesToolingExecutionEntity::getArrangeCode, task.getCode())
                .eq(MesToolingExecutionEntity::getStatus, "CHECKED_IN")
                .set(MesToolingExecutionEntity::getStatus, "RELEASED")
                .set(MesToolingExecutionEntity::getReleaseTime, LocalDateTime.now()));
        LocalDateTime closeTime = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        closeExecutionSegment(task, "CLOSE", "CANCELLED", closeTime);
        workpieceTimeService.finishForArrange(task, closeTime, "CLOSE");
        if (!this.update(new LambdaUpdateWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getId, id)
                .set(MesTaskArrangeEntity::getStatus, MesProduceTaskStatusEnum.CLOSE.name()))) {
            throw new CheckedException("派工关闭事实保存失败");
        }
        task.setStatus(MesProduceTaskStatusEnum.CLOSE.name());
        // 可以停止已关闭父对象的残留派工，但不能由此恢复父对象或再次回传完工。
        if (!terminalParent(produceTask.getStatus()) && !terminalParent(mainTask.getStatus())) {
            completeParents(task, produceTask, mainTask, closeTime, true);
        }
    }

    /**
     * 完工
     *
     * @param id
     */
    @Override
    @org.springframework.security.access.prepost.PreAuthorize("@pms.hasPermission('mesProduceTask:finish')")
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void finish(Long id) {
        MesTaskArrangeEntity arrange = lockedForStateChange(id);
        terminalExecutionGuard.assertUnoccupied(arrange);
        assertAssignedWorker(arrange);
        if (!arrange.getStatus().equals(MesProduceTaskStatusEnum.PRODUCT.name())) {
            throw new RuntimeException("只有加工中任务才能完工");
        }
        MesProduceTaskEntity produceTask = frozenParent(arrange);
        MesTaskEntity mainTask = mainTaskForArrange(arrange);
        if (terminalParent(produceTask.getStatus()) || terminalParent(mainTask.getStatus())) {
            throw new CheckedException("父工序或工单已经关闭/完工，残留派工不能重新完成");
        }
        produceTask = checkFinishQuality(produceTask, mainTask);
        consumeToolingLife(arrange);
        LocalDateTime finishTime = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        closeExecutionSegment(arrange, "FINISH", "CLOSED", finishTime);
        workpieceTimeService.finishForArrange(arrange, finishTime, "FINISH");
        arrange.setFinishTime(finishTime);
        arrange.setFinishHour(getFinishHour(arrange.getStartTime(), arrange.getFinishTime()));
        arrange.setStatus(MesProduceTaskStatusEnum.FINISH.name());
        // 质量同步刚刚可能增加合格放行量，只写完工字段，禁止旧整实体覆盖本事务的新质量数量。
        if (!this.update(new LambdaUpdateWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, arrange.getId())
                .eq(MesTaskArrangeEntity::getTenantId, arrange.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, arrange.getFactoryCode())
                .set(MesTaskArrangeEntity::getFinishTime, arrange.getFinishTime())
                .set(MesTaskArrangeEntity::getFinishHour, arrange.getFinishHour())
                .set(MesTaskArrangeEntity::getStatus, MesProduceTaskStatusEnum.FINISH.name()))) {
            throw new CheckedException("派工完工事实保存失败");
        }
        completeParents(arrange, produceTask, mainTask, finishTime, false);
    }

    private MesProduceTaskEntity checkFinishQuality(MesProduceTaskEntity produceTask, MesTaskEntity mainTask) {
        MesQualityGateDecision qualityGate;
        if ("REWORK".equals(mainTask.getResourceType())) {
            // 返工工序可以完成并触发复检，但产出数量仍保持隔离，不走普通质量门自动放行。
            qualityGate = new MesQualityGateDecision().setAllowed(Boolean.TRUE).setPassed(Boolean.FALSE)
                    .setGateMode("BLOCK").setPhase("FINISH").setMessage("返工完工后等待独立复检");
        } else {
            qualityGate = qualityGateService.checkFinish(produceTask, mainTask);
            qualityGate = qualityQuantityService.reconcile(produceTask.getCode(), qualityGate);
        }
        if (!Boolean.TRUE.equals(qualityGate.getAllowed())) {
            throw new CheckedException("质量门阻断完工：" + qualityGate.getMessage());
        }
        // 数量同步可能刚刚释放了待判产出，刷新工序数量后再决定后序放行时刻。
        var refreshed = produceTaskService.getById(produceTask.getId());
        if (refreshed == null) throw new CheckedException("质量同步后原工序任务不存在");
        return refreshed;
    }

    private MesTaskEntity mainTaskForArrange(MesTaskArrangeEntity arrange) {
        return mainTaskForArrange(arrange, false);
    }

    private MesTaskEntity mainTaskForArrange(MesTaskArrangeEntity arrange, boolean lock) {
        MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, arrange.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()).last(lock, "FOR UPDATE"));
        if (task == null || task.getId() == null || !Objects.equals(task.getTenantId(), arrange.getTenantId())
                || !Objects.equals(task.getFactoryCode(), arrange.getFactoryCode())
                || Arrays.stream(MesTaskStatusEnum.values()).noneMatch(state -> state.name().equals(task.getStatus()))) {
            throw new CheckedException("未找到派工任务在实际租户工厂对应的生产工单");
        }
        return task;
    }

    private boolean terminalParent(String status) {
        return status != null && Set.of("CLOSE", "FINISH").contains(status);
    }

    private void completeParents(MesTaskArrangeEntity arrange, MesProduceTaskEntity produceTask,
                                 MesTaskEntity mainTask, LocalDateTime finishTime, boolean checkQuality) {
        // 本派工已结束不代表同工序的其他班次/人员派工已结束；按完整原任务集汇总。
        var completion = completionGuard.evaluate(arrange);
        if (completion.processStatus() == null) return;
        if (checkQuality && MesProduceTaskStatusEnum.FINISH.name().equals(completion.processStatus())) {
            produceTask = checkFinishQuality(produceTask, mainTask);
            completion = completionGuard.evaluate(arrange);
        }
        boolean processFinished = MesProduceTaskStatusEnum.FINISH.name().equals(completion.processStatus());
        var processUpdate = new LambdaUpdateWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getId, produceTask.getId())
                .eq(MesProduceTaskEntity::getTenantId, arrange.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesProduceTaskEntity::getTaskCode, arrange.getTaskCode())
                .eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode())
                .eq(MesProduceTaskEntity::getStatus, produceTask.getStatus())
                .notIn(MesProduceTaskEntity::getStatus, "CLOSE", "FINISH")
                .set(MesProduceTaskEntity::getStatus, completion.processStatus())
                .set(MesProduceTaskEntity::getFinishTime, processFinished ? finishTime : null);
        if (processFinished && produceTask.getTransferReleasedTime() == null
                && Optional.ofNullable(produceTask.getQualityReleasedNum()).orElse(BigDecimal.ZERO)
                .compareTo(Optional.ofNullable(produceTask.getCompleteNum()).orElse(BigDecimal.ZERO)) >= 0) {
            processUpdate.set(MesProduceTaskEntity::getTransferReleasedTime, finishTime.plusMinutes(
                    Optional.ofNullable(produceTask.getTransferMinutes()).orElse(0L)));
        }
        if (!produceTaskService.update(processUpdate)) throw new CheckedException("工序完工事实保存失败");

        if (completion.completedQuantity() != null) {
            MesTaskEntity task = mainTask;
            BigDecimal completedQuantity = completion.completedQuantity();
            if (!mesTaskService.update(new LambdaUpdateWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getId, task.getId())
                    .eq(MesTaskEntity::getTenantId, arrange.getTenantId())
                    .eq(MesTaskEntity::getFactoryCode, arrange.getFactoryCode())
                    .eq(MesTaskEntity::getCode, arrange.getTaskCode())
                    .eq(MesTaskEntity::getStatus, task.getStatus())
                    .notIn(MesTaskEntity::getStatus, "CLOSE", "FINISH")
                    .set(MesTaskEntity::getStatus, MesTaskStatusEnum.FINISH.name())
                    .set(MesTaskEntity::getFinishTime, new Date())
                    .set(MesTaskEntity::getCompleteNum, completedQuantity))) {
                throw new CheckedException("生产工单完工事实保存失败");
            }
            if (task != null && StrUtil.isNotBlank(task.getPlanNo())) {
                terminalExecutionGuard.beforeProductionRemoteWrite();
                mesTaskService.feedbackPlanCompletion(task, completedQuantity);
            }
            if (task != null && "REWORK".equals(task.getResourceType())) {
                terminalExecutionGuard.beforeProductionRemoteWrite();
                // 本事务保存发送原件，提交后投递；本地完工不代表独立复检已确认或质量放行。
                mesTaskService.notifyReworkCompletion(task.getId(), produceTask.getCode(), completedQuantity);
            }
        }
    }

    /**
     * 设备开机/换模
     *
     * @param dto
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deviceStart(DeviceCheckTaskStartDto dto) {
        if (dto == null || StrUtil.hasBlank(dto.getDeviceCode(), dto.getWorksiteCode())
                || dto.getDeviceCode().length() > 100 || dto.getWorksiteCode().length() > 100) {
            throw new CheckedException("设备开机必须明确设备和工位，编码不得超过100字");
        }
        String factory = actualFactoryAccess.require("write");
        if (Boolean.TRUE.equals(dto.getChangeMold())) {
            requireChangeoverOwner(dto.getExpectedTenantId(), dto.getExpectedFactoryCode(), dto.getExpectedActorUserId(), factory);
            if (StrUtil.isBlank(dto.getMoldCode()) || dto.getMoldCode().trim().length() > 100) {
                throw new CheckedException("换型目标工装编码必须明确且不超过100字，禁止先调用TPM后因本地超长回滚");
            }
        }
        dto.setDeviceCode(dto.getDeviceCode().trim());
        dto.setWorksiteCode(dto.getWorksiteCode().trim());
        if (StrUtil.isNotBlank(dto.getMoldCode())) dto.setMoldCode(dto.getMoldCode().trim());
        // 开始/明确取消/完成共用设备锁；不在跨服务未知时以普通开机覆盖本地旧绑定。
        changeoverExecutionMapper.lockDevice(dto.getDeviceCode());
        if (!Boolean.TRUE.equals(dto.getChangeMold()) && changeoverExecutionMapper.selectCount(
                changeoverScope(factory).eq(MesChangeoverExecutionEntity::getDeviceCode, dto.getDeviceCode())
                        .eq(MesChangeoverExecutionEntity::getStatus, "STARTED")) > 0) {
            throw new CheckedException("设备存在进行中换型，请先查看原账，不能用普通开机覆盖绑定");
        }
        MesDeviceGateDecision precheck = deviceGateService.checkDeviceStart(
                dto.getDeviceCode(), dto.getWorksiteCode(), null);
        if (!Boolean.TRUE.equals(precheck.getAllowed())) {
            throw new CheckedException("设备运行门阻断开机：" + precheck.getMessage());
        }
        MesWorksiteMoldEntity bind = worksiteMoldService.getOne(new LambdaQueryWrapper<MesWorksiteMoldEntity>()
                .eq(MesWorksiteMoldEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesWorksiteMoldEntity::getFactoryCode, factory)
                .eq(MesWorksiteMoldEntity::getWorksiteCode, dto.getWorksiteCode())
                .eq(MesWorksiteMoldEntity::getDeviceCode, dto.getDeviceCode()).last("FOR UPDATE"));
        MomUser user = SecurityUtils.getUser();
        String content = "";
        // 换模
        if (Boolean.TRUE.equals(dto.getChangeMold())) {
            MesChangeoverExecutionEntity execution = requireStartedChangeover(dto);
            if (StrUtil.isEmpty(dto.getMoldCode())) {
                throw new RuntimeException("请扫描换模模具");
            }
            if (bind == null || StrUtil.isBlank(bind.getMoldCode())) {
                throw new CheckedException("当前工位与设备尚未绑定旧模具，不能直接执行换模，请先完成设备开机绑定");
            }
            String oldMoldCode = bind.getMoldCode();
            if (oldMoldCode.equals(dto.getMoldCode())) {
                throw new RuntimeException("已绑定此模具" + dto.getMoldCode() + "，无需换模");
            }
            if (!Objects.equals(execution.getFromToolingCode(), oldMoldCode)) {
                throw new CheckedException("换型开始后设备绑定工装已变化，请停止写操作并核对MES/TPM/MDM原事实，不以取消计时掩盖未知结果");
            }
            // 设备换模
            R<Boolean> changeResult = remoteDeviceCheckService.changeMold(new DeviceChangeMoldDto()
                    .setOldMoldCode(oldMoldCode).setNewMoldCode(dto.getMoldCode())
                    .setNewMoldDetailsList(dto.getMoldDetailsList()));
            if (changeResult == null || changeResult.getCode() != 0 || !Boolean.TRUE.equals(changeResult.getData())) {
                throw new CheckedException("TPM换模失败：" + (changeResult == null ? "无响应" : changeResult.getMsg()));
            }
            content = user.getName() + "于" + DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "进行换模，模具编码为：" + dto.getMoldCode();
            bind.setMoldCode(dto.getMoldCode());
            MdmDeviceEntity queryEntity = new MdmDeviceEntity();
            queryEntity.setDeviceCode(dto.getMoldCode());
            R<MdmDeviceEntity> moldResponse = deviceService.getDetails(queryEntity);
            MdmDeviceEntity moldDetail = moldResponse == null ? null : moldResponse.getData();
            if (Objects.nonNull(moldDetail)) {
                bind.setMoldName(moldDetail.getDeviceName());
            }
        } else {
            if (Objects.isNull(bind)) {
                bind = new MesWorksiteMoldEntity();
                bind.setWorksiteCode(dto.getWorksiteCode());
                bind.setDeviceCode(dto.getDeviceCode());
            }
            bind.setMoldCode(dto.getMoldCode());
            if (StrUtil.isNotBlank(dto.getMoldCode())) {
                MdmDeviceEntity queryEntity = new MdmDeviceEntity();
                queryEntity.setDeviceCode(dto.getMoldCode());
                R<MdmDeviceEntity> moldResponse = deviceService.getDetails(queryEntity);
                MdmDeviceEntity moldDetail = moldResponse == null ? null : moldResponse.getData();
                if (Objects.nonNull(moldDetail)) {
                    bind.setMoldName(moldDetail.getDeviceName());
                }
            }
            // 设备开机
            R<Map<String, Object>> startResponse = remoteDeviceCheckService.start(dto);
            if (startResponse == null || startResponse.getCode() != 0 || startResponse.getData() == null) {
                throw new CheckedException("TPM设备开机失败：" + (startResponse == null ? "无响应" : startResponse.getMsg()));
            }
            Map<String, Object> startResult = startResponse.getData();
            if (!Boolean.TRUE.equals(startResult.get("data"))) {
                throw new RuntimeException(String.valueOf(startResult.get("msg")));
            }
            content = user.getName() + "于" + DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "进行设备开机，设备编码为：" + dto.getDeviceCode() + "模具编码为：" + dto.getMoldCode();
        }

        worksiteMoldService.saveOrUpdate(bind);

        // 保存开机/换模记录
        MdmDeviceEntity deviceQuery = new MdmDeviceEntity();
        deviceQuery.setDeviceCode(dto.getDeviceCode());
        R<MdmDeviceEntity> deviceResponse = deviceService.getDetails(deviceQuery);
        MdmDeviceEntity deviceEntity = deviceResponse == null ? null : deviceResponse.getData();
        MdmDeviceEntity moldEntity = null;
        if (StrUtil.isNotBlank(dto.getMoldCode())) {
            MdmDeviceEntity moldQuery = new MdmDeviceEntity();
            moldQuery.setDeviceCode(dto.getMoldCode());
            R<MdmDeviceEntity> moldResponse = deviceService.getDetails(moldQuery);
            moldEntity = moldResponse == null ? null : moldResponse.getData();
        }
        MesTaskDeviceRecordEntity deviceRecord = new MesTaskDeviceRecordEntity()
                .setDeviceCode(dto.getDeviceCode())
                .setDeviceName(Objects.nonNull(deviceEntity) ? deviceEntity.getDeviceName() : "")
                .setMoldCode(dto.getMoldCode())
                .setMoldName(Objects.nonNull(moldEntity) ? moldEntity.getDeviceName() : "")
                .setDeviceDetails(JSONUtil.toJsonStr(dto.getDeviceDetailsList()))
                .setMoldDetails(JSONUtil.toJsonStr(dto.getMoldDetailsList()))
                .setWorksiteCode(dto.getWorksiteCode())
                .setChangeMold(Boolean.TRUE.equals(dto.getChangeMold()))
                .setContent(content);
        deviceRecordService.save(deviceRecord);
        if (Boolean.TRUE.equals(dto.getChangeMold())) {
            completeChangeover(dto, deviceRecord, moldEntity);
        }

    }

    @Override
    public List<MesTaskExecutionSegmentEntity> listExecutionSegments(Long arrangeId) {
        if (arrangeId == null) throw new CheckedException("派工任务ID不能为空");
        return executionSegmentMapper.selectList(new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                .eq(MesTaskExecutionSegmentEntity::getArrangeId, arrangeId)
                .orderByAsc(MesTaskExecutionSegmentEntity::getSegmentNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesChangeoverExecutionEntity beginChangeover(MesChangeoverStartDTO dto) {
        if (dto == null || StrUtil.hasBlank(dto.getDeviceCode(), dto.getWorksiteCode(), dto.getRequestId())) {
            throw new CheckedException("开始换型必须提供请求号、设备和工位");
        }
        if (dto.getRequestId().length() > 100 || dto.getDeviceCode().length() > 100 || dto.getWorksiteCode().length() > 100) {
            throw new CheckedException("开始原号、设备和工位编码不能超过100字");
        }
        String factory = actualFactoryAccess.require("write");
        requireChangeoverOwner(dto.getExpectedTenantId(), dto.getExpectedFactoryCode(), dto.getExpectedActorUserId(), factory);
        dto.setRequestId(dto.getRequestId().trim());
        dto.setDeviceCode(dto.getDeviceCode().trim());
        dto.setWorksiteCode(dto.getWorksiteCode().trim());
        MomUser user = SecurityUtils.getUser();
        changeoverExecutionMapper.lockDevice(dto.getDeviceCode());
        MesChangeoverExecutionEntity byRequest = changeoverExecutionMapper.selectOne(
                changeoverScope(factory).eq(MesChangeoverExecutionEntity::getRequestId, dto.getRequestId()));
        if (byRequest != null) {
            if (!Objects.equals(byRequest.getDeviceCode(), dto.getDeviceCode())
                    || !Objects.equals(byRequest.getWorksiteCode(), dto.getWorksiteCode())
                    || !Objects.equals(byRequest.getStartedBy(), user.getUsername())) {
                throw new CheckedException("开始原号已被不同设备、工位或账号使用，不得覆盖");
            }
            return byRequest;
        }
        MesChangeoverExecutionEntity active = changeoverExecutionMapper.selectOne(
                changeoverScope(factory)
                        .eq(MesChangeoverExecutionEntity::getDeviceCode, dto.getDeviceCode())
                        .eq(MesChangeoverExecutionEntity::getStatus, "STARTED")
                        .last("FOR UPDATE"));
        if (active != null) {
            if (!Objects.equals(active.getWorksiteCode(), dto.getWorksiteCode())) {
                throw new CheckedException("设备已有其他工位发起的未完成换型，请先处理执行账[" + active.getId() + "]");
            }
            throw new CheckedException("设备已有进行中换型，请通过只读入口恢复原账，不另造开始原号");
        }
        MesWorksiteMoldEntity bind = worksiteMoldService.getOne(new LambdaQueryWrapper<MesWorksiteMoldEntity>()
                .eq(MesWorksiteMoldEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesWorksiteMoldEntity::getFactoryCode, factory)
                .eq(MesWorksiteMoldEntity::getWorksiteCode, dto.getWorksiteCode())
                .eq(MesWorksiteMoldEntity::getDeviceCode, dto.getDeviceCode()).last("FOR UPDATE"));
        if (bind == null || StrUtil.isBlank(bind.getMoldCode())) {
            throw new CheckedException("当前工位与设备尚未绑定旧工装，不能开始换型");
        }
        MesChangeoverExecutionEntity row = new MesChangeoverExecutionEntity()
                .setRequestId(dto.getRequestId().trim()).setStatus("STARTED")
                .setWorksiteCode(dto.getWorksiteCode().trim()).setDeviceCode(dto.getDeviceCode().trim())
                .setFromToolingCode(bind.getMoldCode()).setFromToolingName(bind.getMoldName())
                .setStartedTime(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                .setStartedBy(user.getUsername()).setStartedByName(user.getName())
                .setSampleEligible(Boolean.FALSE).setExclusionReason("换型尚未完成")
                .setFactoryCode(factory);
        MdmDeviceEntity query = new MdmDeviceEntity();
        query.setDeviceCode(dto.getDeviceCode());
        R<MdmDeviceEntity> deviceResponse = deviceService.getDetails(query);
        if (deviceResponse == null || deviceResponse.getData() == null) {
            throw new CheckedException("无法读取设备主档，禁止开始无来源换型计时");
        }
        row.setDeviceName(deviceResponse.getData().getDeviceName());
        row.setTenantId(TenantContextHolder.getTenantId());
        if (changeoverExecutionMapper.insert(row) != 1) throw new CheckedException("开始计时账保存失败");
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelChangeover(MesChangeoverCancelDTO dto) {
        if (dto == null || dto.getExecutionId() == null
                || StrUtil.hasBlank(dto.getReason(), dto.getDeviceCode(), dto.getWorksiteCode())
                || dto.getReason().length() > 500 || dto.getDeviceCode().length() > 100 || dto.getWorksiteCode().length() > 100) {
            throw new CheckedException("明确取消必须提供原账、设备、工位和1至500字实际原因");
        }
        String factory = actualFactoryAccess.require("write");
        requireChangeoverOwner(dto.getExpectedTenantId(), dto.getExpectedFactoryCode(), dto.getExpectedActorUserId(), factory);
        dto.setReason(dto.getReason().trim());
        dto.setDeviceCode(dto.getDeviceCode().trim());
        dto.setWorksiteCode(dto.getWorksiteCode().trim());
        changeoverExecutionMapper.lockDevice(dto.getDeviceCode());
        MesChangeoverExecutionEntity row = changeoverExecutionMapper.selectOne(
                changeoverScope(factory)
                        .eq(MesChangeoverExecutionEntity::getId, dto.getExecutionId()).last("FOR UPDATE"));
        if (row == null) throw new CheckedException("换型执行账不存在");
        if (!Objects.equals(row.getDeviceCode(), dto.getDeviceCode())
                || !Objects.equals(row.getWorksiteCode(), dto.getWorksiteCode())) {
            throw new CheckedException("取消目标与当前组织、设备或工位不一致");
        }
        MomUser user = SecurityUtils.getUser();
        if ("CANCELLED".equals(row.getStatus())) {
            if (!Objects.equals(row.getCancelReason(), dto.getReason())
                    || !Objects.equals(row.getCancelledBy(), user.getUsername())) {
                throw new CheckedException("原账已由不同账号或不同依据取消，请查原事实，不冒充本次取消成功");
            }
            return;
        }
        if (!"STARTED".equals(row.getStatus())) throw new CheckedException("只有进行中的换型可以取消");
        row.setStatus("CANCELLED").setCancelledTime(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                .setCancelledBy(user.getUsername()).setCancelledByName(user.getName())
                .setCancelReason(dto.getReason())
                // 实际依据完整存于cancelReason；排除摘要不拼前缀造成varchar(500)超限。
                .setSampleEligible(Boolean.FALSE).setExclusionReason("换型已明确取消，实际依据见取消原因");
        if (changeoverExecutionMapper.updateById(row) != 1) throw new CheckedException("明确取消账保存失败");
    }

    private MesTaskExecutionSegmentEntity openExecutionSegment(MesTaskArrangeEntity task, LocalDateTime now) {
        now = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        MesTaskExecutionSegmentEntity active = executionSegmentMapper.selectOne(
                new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                        .eq(MesTaskExecutionSegmentEntity::getArrangeId, task.getId())
                        .eq(MesTaskExecutionSegmentEntity::getStatus, "OPEN").last("limit 1 FOR UPDATE"));
        if (active != null) throw new CheckedException("派工任务已有未关闭生产时间段，禁止重复开工");
        List<MesTaskExecutionSegmentEntity> existing = executionSegmentMapper.selectList(
                new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                        .eq(MesTaskExecutionSegmentEntity::getArrangeId, task.getId())
                        .orderByDesc(MesTaskExecutionSegmentEntity::getSegmentNo).last("limit 1"));
        int nextNo = existing.isEmpty() ? 1 : Optional.ofNullable(existing.get(0).getSegmentNo()).orElse(0) + 1;
        if (existing.isEmpty() && task.getStartTime() != null && task.getStartTime().isBefore(now.minusSeconds(1))) {
            executionSegmentMapper.insert(segmentOf(task, 1, task.getStartTime(), now, "CLOSED", "LEGACY_GAP",
                    false, "升级前任务缺少暂停区间，历史墙钟时间不参与校准"));
            nextNo = 2;
        }
        MesTaskExecutionSegmentEntity opened = segmentOf(task, nextNo, now, null, "OPEN", null, true, null);
        if (executionSegmentMapper.insert(opened) != 1) throw new CheckedException("加工时间段保存失败");
        return opened;
    }

    private void closeExecutionSegment(MesTaskArrangeEntity task, String reason, String targetStatus,
                                       LocalDateTime now) {
        // 先规范为PG timestamp默认微秒精度再落整秒，防止数据库舍入使段秒数与原时刻不同。
        now = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        MesTaskExecutionSegmentEntity active = executionSegmentMapper.selectOne(
                new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                        .eq(MesTaskExecutionSegmentEntity::getArrangeId, task.getId())
                        .eq(MesTaskExecutionSegmentEntity::getStatus, "OPEN").last("limit 1 FOR UPDATE"));
        if (active == null) {
            if (task.getStartTime() != null) {
                List<MesTaskExecutionSegmentEntity> latest = executionSegmentMapper.selectList(
                        new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                                .eq(MesTaskExecutionSegmentEntity::getArrangeId, task.getId())
                                .orderByDesc(MesTaskExecutionSegmentEntity::getSegmentNo).last("limit 1"));
                int no = latest.isEmpty() ? 1 : Optional.ofNullable(latest.get(0).getSegmentNo()).orElse(0) + 1;
                if (executionSegmentMapper.insert(segmentOf(task, no, task.getStartTime(), now, targetStatus,
                        "LEGACY_GAP", false, "升级前任务缺少连续时间段，不能作为校准样本")) != 1) {
                    throw new CheckedException("历史加工缺口段保存失败");
                }
            }
            return;
        }
        active.setEndTime(now).setDurationSeconds(Math.max(0L, Duration.between(active.getStartTime(), now).getSeconds()))
                .setStatus(targetStatus).setCloseReason(reason);
        if ("CANCELLED".equals(targetStatus)) {
            active.setSampleEligible(Boolean.FALSE).setExclusionReason("派工任务未正常完工");
        }
        if (executionSegmentMapper.updateById(active) != 1) throw new CheckedException("加工时间段关闭保存失败");
    }

    private MesTaskExecutionSegmentEntity segmentOf(MesTaskArrangeEntity task, int no, LocalDateTime start,
                                                    LocalDateTime end, String status, String closeReason,
                                                    boolean eligible, String exclusion) {
        MesTaskExecutionSegmentEntity segment = new MesTaskExecutionSegmentEntity().setArrangeId(task.getId()).setArrangeCode(task.getCode())
                .setSegmentNo(no).setSegmentType("RUN").setStartTime(start).setEndTime(end)
                .setDurationSeconds(end == null ? null : Math.max(0L, Duration.between(start, end).getSeconds()))
                .setStatus(status).setCloseReason(closeReason).setSampleEligible(eligible)
                .setExclusionReason(exclusion).setTaskCode(task.getTaskCode())
                .setProduceTaskCode(task.getProduceTaskCode()).setProcessCode(task.getRouteCode())
                .setMaterialCode(task.getMatCode())
                .setStationCode(StrUtil.blankToDefault(task.getActualWorksiteCode(), task.getPlannedWorksiteCode()))
                .setDeviceCode(task.getPlannedDeviceCode()).setWorkerCodes(task.getWorkerCode())
                .setFactoryCode(task.getFactoryCode());
        segment.setTenantId(task.getTenantId());
        return segment;
    }

    private MesChangeoverExecutionEntity requireStartedChangeover(DeviceCheckTaskStartDto dto) {
        if (dto.getChangeoverExecutionId() == null) {
            throw new CheckedException("换模必须先调用开始换型建立计时账");
        }
        MesChangeoverExecutionEntity row = changeoverExecutionMapper.selectOne(
                changeoverScope(actualFactoryAccess.require("write"))
                        .eq(MesChangeoverExecutionEntity::getId, dto.getChangeoverExecutionId()).last("FOR UPDATE"));
        if (row == null || !"STARTED".equals(row.getStatus())) {
            throw new CheckedException("换型执行账不存在或已结束");
        }
        if (!Objects.equals(row.getDeviceCode(), dto.getDeviceCode())
                || !Objects.equals(row.getWorksiteCode(), dto.getWorksiteCode())) {
            throw new CheckedException("换型执行账与当前设备或工位不一致");
        }
        return row;
    }

    /**
     * 所有物理换型原账入口显式限定实际组织；不依赖调用者可跳过的隐式过滤。
     */
    private LambdaQueryWrapper<MesChangeoverExecutionEntity> changeoverScope(String factory) {
        return new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
                .eq(MesChangeoverExecutionEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesChangeoverExecutionEntity::getFactoryCode, factory);
    }

    /**
     * 期望值只作比对，绝不用于runAs或设置请求隔离上下文。
     */
    private void requireChangeoverOwner(String tenant, String factory, String actor, String actualFactory) {
        if (!Objects.equals(tenant, TenantContextHolder.getTenantId().toString())
                || !Objects.equals(factory, actualFactory)
                || !Objects.equals(actor, SecurityUtils.getUser().getId().toString())) {
            throw new CheckedException("观察后的实名或组织已改变，请停止原动作并重新查看，不能换头提交旧目标");
        }
    }

    private void completeChangeover(DeviceCheckTaskStartDto dto, MesTaskDeviceRecordEntity deviceRecord,
                                    MdmDeviceEntity targetTooling) {
        MesChangeoverExecutionEntity row = requireStartedChangeover(dto);
        LocalDateTime completed = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        long seconds = Math.max(0L, Duration.between(row.getStartedTime(), completed).getSeconds());
        MomUser user = SecurityUtils.getUser();
        boolean eligible = seconds > 0 && seconds <= Duration.ofHours(24).getSeconds();
        row.setStatus("COMPLETED").setToToolingCode(dto.getMoldCode())
                .setToToolingName(targetTooling == null ? dto.getMoldCode() : targetTooling.getDeviceName())
                .setCompletedTime(completed).setCompletedBy(user.getUsername()).setCompletedByName(user.getName())
                .setDurationSeconds(seconds).setInspectionSnapshot(JSONUtil.toJsonStr(dto.getMoldDetailsList()))
                .setDeviceRecordId(deviceRecord.getId()).setSampleEligible(eligible)
                .setExclusionReason(eligible ? null : "换型耗时为0或超过24小时，需人工复核后才能参与校准");
        changeoverExecutionMapper.updateById(row);
    }


    /**
     * 同一工位是否已存在加工任务
     *
     * @param worksiteCode
     * @return
     */
    private boolean hasStartTask(String worksiteCode, String workshopCode, String shiftCode, Long taskId) {
        List<String> statusList = new ArrayList<>();
        statusList.add(MesProduceTaskStatusEnum.RECEIVE.name());
        if (Objects.isNull(taskId)) {
            statusList.add(MesProduceTaskStatusEnum.PAUSE.name());
        }
        statusList.add(MesProduceTaskStatusEnum.PRODUCT.name());
        long count = this.count(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .apply("(',' || worker_code || ',') LIKE {0}", "%," + SecurityUtils.getUser().getUsername() + ",%")
                .ne(Objects.nonNull(taskId), MesTaskArrangeEntity::getId, taskId)
                .eq(MesTaskArrangeEntity::getShiftCode, shiftCode)
                .and(item -> item.eq(MesTaskArrangeEntity::getActualWorksiteCode, worksiteCode)
                        .or(nested -> nested.isNull(MesTaskArrangeEntity::getActualWorksiteCode)
                                .apply("(',' || worksite_codes || ',') LIKE {0}", "%," + worksiteCode + ",%")))
//                .eq(MesTaskArrangeEntity::getWorkshopCode, workshopCode)
                .in(MesTaskArrangeEntity::getStatus, statusList));
        if (count > 0) {
            return Boolean.TRUE;
        }
        return false;
    }


    private Integer getFinishHour(LocalDateTime startTime, LocalDateTime endTime) {
        Duration duration = Duration.between(startTime, endTime);
        return (int) duration.toHours();
    }
}
