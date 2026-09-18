package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.dto.MdmToolingAvailabilityQueryDTO;
import com.kitex.mom.mdm.dto.MdmToolingCandidateDTO;
import com.kitex.mom.mdm.dto.MdmToolingUsageLineDTO;
import com.kitex.mom.mdm.dto.MdmToolingUsageRequestDTO;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.mdm.feign.RemoteMdmToolingService;
import com.kitex.mom.mdm.feign.RemoteWorkCenterService;
import com.kitex.mom.mes.batch.BatchGenerator;
import com.kitex.mom.mes.batch.BatchGeneratorService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.MesDeviceGateStageEnum;
import com.kitex.mom.mes.enums.MesTaskStatusEnum;
import com.kitex.mom.mes.enums.WorksiteTypeEnum;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesChangeoverExecutionMapper;
import com.kitex.mom.mes.mapper.MesTaskExecutionSegmentMapper;
import com.kitex.mom.mes.mapper.MesToolingExecutionMapper;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.oms.enums.OrderLongStateEnum;
import com.kitex.mom.plan.feign.RemotePlnIssueService;
import com.kitex.mom.tpm.api.feign.RemoteDeviceCheckService;
import com.kitex.mom.tpm.api.feign.RemoteTpmDeviceService;
import com.kitex.mom.tpm.dto.DeviceChangeMoldDto;
import com.kitex.mom.tpm.dto.DeviceCheckTaskStartDto;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
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
    private final MesTaskExecutionSegmentMapper executionSegmentMapper;
    private final MesChangeoverExecutionMapper changeoverExecutionMapper;

    private static void calculateFeedAllNum(MdmBomChildDTO node, Map<String, BigDecimal> feedMap, Map<String, BigDecimal> usedMap) {
        BigDecimal totalFeedNum = BigDecimal.ZERO;
        BigDecimal totalUsedNum = BigDecimal.ZERO;
        node.setNeedNum(node.getNoticeNum().multiply(node.getQuantity()));

        // 如果当前节点匹配到 feedMap，则累加其值
        if (feedMap.containsKey(node.getMaterialCode())) {
            totalFeedNum = totalFeedNum.add(feedMap.get(node.getMaterialCode()));
        }
        // 如果当前节点匹配到 usedMap，则累加其值
        if (usedMap.containsKey(node.getMaterialCode())) {
            totalUsedNum = totalUsedNum.add(usedMap.get(node.getMaterialCode()));
        }

        // 如果有子节点，则递归计算子节点的总和
        if (node instanceof MdmBomChildListDTO && ((MdmBomChildListDTO) node).getChildren() != null) {
            for (MdmBomChildDTO child : ((MdmBomChildListDTO) node).getChildren()) {
                calculateFeedAllNum(child, feedMap, usedMap); // 递归处理子节点
                totalFeedNum = totalFeedNum.add(Optional.ofNullable(child.getFeedNum()).orElse(BigDecimal.ZERO)); // 累加子节点的 feedNum
                totalUsedNum = totalUsedNum.add(Optional.ofNullable(child.getUsedNum()).orElse(BigDecimal.ZERO)); // 累加子节点的 usedNum
            }
        }
        // 回填当前节点的 feedNum 和 usedNum 字段
        node.setFeedNum(totalFeedNum);
        node.setUsedNum(totalUsedNum);
    }

    /**
     * 批量派工
     *
     * @param dto
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void arrange(MesTaskArrangeDTO dto) {
        long existCount = this.count(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getShiftCode, dto.getShiftCode())
                .eq(MesTaskArrangeEntity::getTaskCode, dto.getCode()));
        if (existCount > 0) {
            throw new RuntimeException("该班次已完成派工,无法重复派工");
        }
        MomUser currentUser = SecurityUtils.getUser();
        List<MesTaskArrangeEntity> taskArrangeList = dto.getTaskArrangeList();
        if (CollUtil.isEmpty(taskArrangeList)) {
            throw new RuntimeException("请维护派工信息");
        }

        MesTaskEntity mainTask = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, dto.getCode()));
        if (Objects.isNull(mainTask)) {
            throw new CheckedException("生产工单不存在，派工失败");
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
            newTask.setProduceTaskCode(produceTask.getCode());
            newTask.setWorkerCode(StrUtil.isNotBlank(produceTask.getPlannedWorkerCode())
                    ? produceTask.getPlannedWorkerCode() : task.getWorkerCode());
            newTask.setWorkerName(StrUtil.isNotBlank(produceTask.getPlannedWorkerCode())
                    ? produceTask.getPlannedWorkerName() : task.getWorkerName());
            newTask.setWorksiteCodes(String.join(",", selectedWorksites));
            newTask.setWorksiteNames(resolveSelectedValues(produceTask.getWorksiteCodes(),
                    produceTask.getWorksiteNames(), selectedWorksites));
            newTask.setWorksiteTypes(resolveSelectedValues(produceTask.getWorksiteCodes(),
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

            produceTaskCodes.add(newTask.getProduceTaskCode());
            finalTaskArrangeList.add(newTask);
        }

        // 保存派工
        this.saveBatch(finalTaskArrangeList);
        // 更新工序任务状态
        produceTaskService.update(new LambdaUpdateWrapper<MesProduceTaskEntity>().in(MesProduceTaskEntity::getCode, produceTaskCodes)
                .set(MesProduceTaskEntity::getStatus, MesProduceTaskStatusEnum.UN_RECEIVE.name()));
        // 更新工单状态
        mesTaskService.update(new LambdaUpdateWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, dto.getCode())
                .set(MesTaskEntity::getStatus, MesTaskStatusEnum.DISPATCH.name()));
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
        List<MesTaskArrangeEntity> allList = this.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getWorkerCode, SecurityUtils.getUser().getUsername())
                .eq(MesTaskArrangeEntity::getShiftCode, shiftCode)
                .eq(MesTaskArrangeEntity::getWorksiteCodes, worksiteCode)
                .eq(MesTaskArrangeEntity::getWorkshopCode, workshopCode)
        );
//        List<MesTaskArrangeEntity> allList = this.list();
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
        List<String> statusList = new ArrayList<>();
        statusList.add(MesProduceTaskStatusEnum.RECEIVE.name());
        statusList.add(MesProduceTaskStatusEnum.PRODUCT.name());
        statusList.add(MesProduceTaskStatusEnum.PAUSE.name());
        MesArrangeTaskVO result = new MesArrangeTaskVO();
        MomUser currentUser = SecurityUtils.getUser();
        MesTaskArrangeEntity arrange = this.getOne(new QueryWrapper<MesTaskArrangeEntity>()
                .apply("{0} = ANY(string_to_array(worker_code, ','))", currentUser.getUsername())
                .eq("shift_code", shiftCode)
                .apply("{0} = ANY(string_to_array(worksite_codes, ','))", worksiteCode)
                .eq("workshop_code", workshopCode)
                .in("status", statusList));
        if (Objects.isNull(arrange)) {
            return null;
        }
        BeanUtil.copyProperties(arrange, result);
        MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getCode, arrange.getTaskCode()));
        if (Objects.nonNull(task)) {
            result.setProcessCode(task.getProcessRouteCode());
        }
        // 获取当前工序
        MesProduceTaskEntity currentProduceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode()));
        result.setCompleteNum(currentProduceTask.getCompleteNum());
        // DAG汇合工序的可用前序数量取全部直接前置工序完工数量的最小值。
        List<String> predecessorCodes = splitCsv(currentProduceTask.getPredecessorCodes());
        if (!predecessorCodes.isEmpty()) {
            List<MesProduceTaskEntity> predecessorTasks = produceTaskService.list(
                    new LambdaQueryWrapper<MesProduceTaskEntity>()
                            .eq(MesProduceTaskEntity::getTaskCode, currentProduceTask.getTaskCode())
                            .in(MesProduceTaskEntity::getRouteCode, predecessorCodes));
            if (predecessorTasks.size() == predecessorCodes.size()) {
                predecessorTasks.stream().map(MesProduceTaskEntity::getCompleteNum).filter(Objects::nonNull)
                        .min(BigDecimal::compareTo).ifPresent(result::setLastPassNum);
            }
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
    public List<MdmBomChildListDTO> getBomList(String code) {
        MesTaskArrangeEntity arrange = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getCode, code));
        if (Objects.isNull(arrange)) {
            return new ArrayList<>();
        }
        // bom信息
        MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getCode, arrange.getTaskCode()));

        List<MesWarehouseFeedEntity> feedList = feedService.list(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .eq(MesWarehouseFeedEntity::getTaskCode, arrange.getTaskCode()));

        return getBomTree(JSONUtil.toList(task.getBomSnapshot(), MdmBomChildListDTO.class), feedList);
    }

    @Override
    public List<MdmBomChildListDTO> getBomListByTaskCode(String code) {
        // bom信息
        MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getCode, code));

        List<MesWarehouseFeedEntity> feedList = feedService.list(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .eq(MesWarehouseFeedEntity::getTaskCode, code));

        return getBomTree(JSONUtil.toList(task.getBomSnapshot(), MdmBomChildListDTO.class), feedList);
    }

    private List<MdmBomChildListDTO> getBomTree(List<MdmBomChildListDTO> bomList, List<MesWarehouseFeedEntity> feedList) {
        Map<String, BigDecimal> feedMap = feedList.stream()
                .collect(Collectors.toMap(
                        entity -> StrUtil.blankToDefault(entity.getRequirementMatCode(), entity.getMatCode()),
                        entity -> toRequirementEquivalent(entity, entity.getFeedNum()),
                        BigDecimal::add // 合并逻辑
                ));
        Map<String, BigDecimal> usedMap = feedList.stream()
                .collect(Collectors.toMap(
                        entity -> StrUtil.blankToDefault(entity.getRequirementMatCode(), entity.getMatCode()),
                        entity -> toRequirementEquivalent(entity, Optional.ofNullable(entity.getUsedNum()).orElse(BigDecimal.ZERO)),
                        BigDecimal::add // 合并逻辑
                ));
        bomList.forEach(node -> calculateFeedAllNum(node, feedMap, usedMap));
        return bomList;
    }

    private BigDecimal toRequirementEquivalent(MesWarehouseFeedEntity feed, BigDecimal actualNum) {
        BigDecimal ratio = Optional.ofNullable(feed.getSubstituteRatio()).orElse(BigDecimal.ONE);
        if (ratio.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return actualNum.divide(ratio, 6, RoundingMode.HALF_UP);
    }

    /**
     * 暂停
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pause(Long id) {
        MesTaskArrangeEntity task = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, id).last("FOR UPDATE"));
        if (task == null) {
            throw new CheckedException("加工任务不存在");
        }
        assertAssignedWorker(task);
        pauseArrange(task, "MANUAL_PAUSE");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pauseByAndon(String arrangeCode) {
        pauseByAndon(arrangeCode, java.util.UUID.randomUUID().toString());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public com.kitex.mom.tpm.dto.TpmDevicePauseReceipt pauseByAndon(String arrangeCode, String requestId) {
        if (StrUtil.isBlank(arrangeCode)) {
            throw new CheckedException("安灯联动暂停必须关联派工单");
        }
        MesTaskArrangeEntity task = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getCode, arrangeCode)
                .eq(MesTaskArrangeEntity::getFactoryCode, com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode())
                .last("FOR UPDATE"));
        if (task == null) {
            throw new CheckedException("关联派工任务不存在：" + arrangeCode);
        }
        if (MesProduceTaskStatusEnum.PAUSE.name().equals(task.getStatus())) {
            return null;
        }
        return pauseArrange(task, "ANDON_PAUSE", requestId);
    }

    private void pauseArrange(MesTaskArrangeEntity task, String pauseReason) {
        pauseArrange(task, pauseReason, java.util.UUID.randomUUID().toString());
    }

    private com.kitex.mom.tpm.dto.TpmDevicePauseReceipt pauseArrange(MesTaskArrangeEntity task, String pauseReason, String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) throw new CheckedException("暂停尝试请求号无效");
        com.kitex.mom.tpm.dto.TpmDevicePauseReceipt receipt = null;
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
                    if (configured.size() != 1) throw new CheckedException("暂停设备缺少唯一实际工位，不能取多个候选中的第一项");
                    worksite = configured.get(0);
                }
                var pauseBindings = worksiteMoldService.list(
                        new LambdaQueryWrapper<MesWorksiteMoldEntity>()
                                .eq(MesWorksiteMoldEntity::getWorksiteCode, worksite)
                                .eq(MesWorksiteMoldEntity::getFactoryCode, task.getFactoryCode())
                                .eq(MesWorksiteMoldEntity::getDeviceCode, task.getPlannedDeviceCode()).last("LIMIT 2"));
                if (pauseBindings.size() > 1) throw new CheckedException("工位设备模具绑定重复，禁止猜测暂停对象");
                MesWorksiteMoldEntity bind = pauseBindings.isEmpty() ? null : pauseBindings.get(0);
                var command = new com.kitex.mom.tpm.dto.TpmDevicePauseCommand();
                command.setRequestId(requestId); command.setDeviceCode(task.getPlannedDeviceCode());
                command.setMoldCode(bind == null ? null : bind.getMoldCode());
                command.setReason(pauseReason + "；派工ID=" + task.getId() + "；只转换业务状态，不代表PLC停机");
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
        closeExecutionSegment(task, pauseReason, "CLOSED", LocalDateTime.now());
        task.setStatus(MesProduceTaskStatusEnum.PAUSE.name());
        if (!this.updateById(task)) throw new CheckedException("派工暂停状态保存失败，须核对远端回执后重试");
        return receipt;
    }

    /**
     * 接收
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkInToolings(MesToolingCheckInDTO dto) {
        if (dto == null || dto.getArrangeId() == null) {
            throw new CheckedException("派工任务不能为空");
        }
        MesTaskArrangeEntity task = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, dto.getArrangeId()).last("FOR UPDATE"));
        if (task == null) {
            throw new CheckedException("加工任务不存在");
        }
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
        MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getCode, task.getProduceTaskCode()));
        if (produceTask == null || StrUtil.isBlank(produceTask.getProcessRouteCode())) {
            throw new CheckedException("工序任务缺少工艺路线，无法复核工装");
        }
        LocalDateTime checkStart = LocalDateTime.now();
        LocalDateTime checkEnd = task.getPlannedEndTime() != null && task.getPlannedEndTime().isAfter(checkStart)
                ? task.getPlannedEndTime() : checkStart.plusMinutes(1);
        MdmToolingAvailabilityQueryDTO query = new MdmToolingAvailabilityQueryDTO();
        query.setRouteCodes(List.of(produceTask.getProcessRouteCode()));
        query.setProcessCodes(List.of(task.getRouteCode()));
        query.setToolingCodes(new ArrayList<>(planned));
        query.setStartTime(checkStart);
        query.setEndTime(checkEnd);
        R<List<MdmToolingCandidateDTO>> response = remoteMdmToolingService.availability(query);
        List<MdmToolingCandidateDTO> candidates = response == null ? null : response.getData();
        if (CollUtil.isEmpty(candidates)) {
            throw new CheckedException("未取得工装实时能力数据，禁止领用");
        }
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
                            .eq(MesToolingExecutionEntity::getArrangeCode, task.getCode())
                            .eq(MesToolingExecutionEntity::getToolingCode, toolingCode));
            if (execution == null) {
                MdmToolingCandidateDTO candidate = selectedCandidates.get(toolingCode);
                execution = new MesToolingExecutionEntity();
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
                toolingExecutionMapper.insert(execution);
            } else if ("RELEASED".equals(execution.getStatus())
                    && StrUtil.isBlank(execution.getLifeConsumptionRequestId())) {
                execution.setStatus("CHECKED_IN");
                execution.setCheckInWorkerCode(currentUser.getUsername());
                execution.setCheckInWorkerName(currentUser.getName());
                execution.setCheckInTime(LocalDateTime.now());
                execution.setStartTime(null);
                execution.setReleaseTime(null);
                execution.setPlannedLifeUsage(lifeUsage.get(toolingCode));
                toolingExecutionMapper.updateById(execution);
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
        MesTaskArrangeEntity task = this.getById(id);
        if (Objects.isNull(task)) {
            throw new RuntimeException("加工任务不存在");
        }
        if (!(MesProduceTaskStatusEnum.UN_RECEIVE.name().equals(task.getStatus()) || MesProduceTaskStatusEnum.CLOSE.name().equals(task.getStatus()))) {
            throw new RuntimeException("加工任务已被接收");
        }
        assertAssignedWorker(task);
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
        // 判断是否需要换模
        // 查询task表拿到绑定的moldList
        MesTaskEntity mainTask = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getCode, task.getTaskCode()));
        List<ProcessProcedureDTO> processList = JSONUtil.toList(mainTask.getProcessRouteSnapshot(), ProcessProcedureDTO.class);
        ProcessProcedureDTO progress = processList.stream()
                .filter(process -> process.getRouteCode().equals(task.getRouteCode()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("未找到对应的工序信息"));

        List<ProcessProcedureDTO.ProcessProcedureMoldDTO> moldList = progress.getMdmProcessProcedureMoldList();
        if (CollUtil.isNotEmpty(moldList) && StrUtil.isNotEmpty(task.getPlannedDeviceCode())) {
            // 查询工作中心-设备-模具绑定表 拿到唯一的绑定模具
            MesWorksiteMoldEntity worksiteMold = worksiteMoldService.getOne(new LambdaQueryWrapper<MesWorksiteMoldEntity>()
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
        this.updateById(task);
    }

    /**
     * 任务开工
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void start(Long id) {
        MesTaskArrangeEntity task = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, id).last("FOR UPDATE"));
        if (Objects.isNull(task)) {
            throw new RuntimeException("加工任务不存在");
        }
        if (!(task.getStatus().equals(MesProduceTaskStatusEnum.RECEIVE.name())
                || task.getStatus().equals(MesProduceTaskStatusEnum.PAUSE.name()))) {
            throw new RuntimeException("只有已接收、已暂停任务才能开工");
        }
        assertAssignedWorker(task);
        assertToolingsCheckedIn(task);
        sopExecutionService.assertStartAllowed(task);
        andonProductionReleaseService.assertAllowed(task, "开工/复工");
        repairProductionReleaseService.assertAllowed(task, "开工/复工");
        MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getCode, task.getProduceTaskCode()));
        if (produceTask == null) {
            throw new CheckedException("工序任务不存在，不能开工");
        }
        assertPredecessorsFinished(produceTask);
        changePointHandleService.assertStartAllowed(task);

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
        materialStartGate.assertStart(task);

        // TODO 自动/手动 区别判断处理
        String type = task.getWorksiteTypes();
        if (WorksiteTypeEnum.AUTO.name().equals(type)) {

        }
        task.setStatus(MesProduceTaskStatusEnum.PRODUCT.name());
        LocalDateTime startDate = task.getStartTime();
        if (Objects.isNull(startDate)) {
            task.setStartTime(LocalDateTime.now());
        }

        MesTaskExecutionSegmentEntity resumedSegment = openExecutionSegment(task, LocalDateTime.now());

        toolingExecutionMapper.update(null, new LambdaUpdateWrapper<MesToolingExecutionEntity>()
                .eq(MesToolingExecutionEntity::getArrangeCode, task.getCode())
                .eq(MesToolingExecutionEntity::getStatus, "CHECKED_IN")
                .set(MesToolingExecutionEntity::getStatus, "IN_USE")
                .set(MesToolingExecutionEntity::getStartTime, task.getStartTime()));

        this.updateById(task);


        // 更新工序任务
        produceTask.setStatus(MesProduceTaskStatusEnum.PRODUCT.name());
        if (Objects.isNull(produceTask.getStartTime())) {
            produceTask.setStartTime(task.getStartTime());
        }
        produceTaskService.updateById(produceTask);
        repairResumeEventService.recordActualResume(task, resumedSegment);
    }

    /** 并行分支可独立执行，汇合工序必须等待其全部直接前置工序完成。 */
    private void assertPredecessorsFinished(MesProduceTaskEntity current) {
        List<String> predecessorCodes = splitCsv(current.getPredecessorCodes());
        if (predecessorCodes.isEmpty()) {
            return;
        }
        List<MesProduceTaskEntity> predecessors = produceTaskService.list(
                new LambdaQueryWrapper<MesProduceTaskEntity>()
                        .eq(MesProduceTaskEntity::getTaskCode, current.getTaskCode())
                        .in(MesProduceTaskEntity::getRouteCode, predecessorCodes));
        Map<String, MesProduceTaskEntity> byCode = predecessors.stream().collect(Collectors.toMap(
                MesProduceTaskEntity::getRouteCode, item -> item, (a, b) -> a, LinkedHashMap::new));
        List<String> blocking = predecessorCodes.stream().filter(code -> {
            MesProduceTaskEntity predecessor = byCode.get(code);
            return predecessor == null || !predecessorReleased(predecessor, LocalDateTime.now());
        }).map(code -> {
            MesProduceTaskEntity predecessor = byCode.get(code);
            return code + "(" + (predecessor == null ? "缺失" : predecessor.getStatus()
                    + ",已完成" + Optional.ofNullable(predecessor.getCompleteNum()).orElse(BigDecimal.ZERO)
                    + (Boolean.TRUE.equals(predecessor.getOverlapEnabled()) ? "/需转移"
                    + predecessor.getTransferBatchQuantity() : "")) + ")";
        }).toList();
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
        R<?> response = remoteMdmToolingService.consumeLife(request);
        if (response == null || response.getData() == null) {
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
    @Transactional(rollbackFor = Exception.class)
    public void close(Long id) {
        MesTaskArrangeEntity task = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, id).last("FOR UPDATE"));
        if (task == null) {
            throw new CheckedException("加工任务不存在");
        }
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
        closeExecutionSegment(task, "CLOSE", "CANCELLED", LocalDateTime.now());
        this.update(new LambdaUpdateWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getId, id)
                .set(MesTaskArrangeEntity::getStatus, MesProduceTaskStatusEnum.CLOSE.name()));
    }

    /**
     * 完工
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finish(Long id) {
        MesTaskArrangeEntity arrange = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, id).last("FOR UPDATE"));
        if (Objects.isNull(arrange)) {
            throw new RuntimeException("参数有误");
        }
        if (!arrange.getStatus().equals(MesProduceTaskStatusEnum.PRODUCT.name())) {
            throw new RuntimeException("只有加工中任务才能完工");
        }
        MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode()));
        if (produceTask == null) {
            throw new CheckedException("未找到派工任务对应的工序任务");
        }
        MesTaskEntity mainTask = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()));
        if (mainTask == null) {
            throw new CheckedException("未找到派工任务对应的生产工单");
        }
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
        produceTask = produceTaskService.getById(produceTask.getId());
        consumeToolingLife(arrange);
        LocalDateTime finishTime = LocalDateTime.now();
        closeExecutionSegment(arrange, "FINISH", "CLOSED", finishTime);
        arrange.setFinishTime(finishTime);
        arrange.setFinishHour(getFinishHour(arrange.getStartTime(), arrange.getFinishTime()));
        arrange.setStatus(MesProduceTaskStatusEnum.FINISH.name());
        this.updateById(arrange);

        // 更新工序计划表
        produceTask.setStatus(MesProduceTaskStatusEnum.FINISH.name())
                .setFinishTime(LocalDateTime.now());
        if (produceTask.getTransferReleasedTime() == null
                && Optional.ofNullable(produceTask.getQualityReleasedNum()).orElse(BigDecimal.ZERO)
                .compareTo(Optional.ofNullable(produceTask.getCompleteNum()).orElse(BigDecimal.ZERO)) >= 0) {
            produceTask.setTransferReleasedTime(produceTask.getFinishTime().plusMinutes(
                    Optional.ofNullable(produceTask.getTransferMinutes()).orElse(0L)));
        }

        produceTaskService.updateById(produceTask);

        // 以整张工单的全部DAG工序为范围判断完成，避免原逻辑只查询当前工序而提前关闭工单。
        long unfinishedCount = produceTaskService.count(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTaskCode, arrange.getTaskCode())
                .ne(MesProduceTaskEntity::getStatus, MesProduceTaskStatusEnum.FINISH.name()));
        if (unfinishedCount == 0L) {
            MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getCode, arrange.getTaskCode()));
            BigDecimal completedQuantity = produceTaskService.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                            .eq(MesProduceTaskEntity::getTaskCode, arrange.getTaskCode()))
                    .stream().filter(item -> Boolean.TRUE.equals(item.getLastRoute()))
                    .map(MesProduceTaskEntity::getCompleteNum).filter(Objects::nonNull)
                    .min(BigDecimal::compareTo).orElse(Optional.ofNullable(produceTask.getCompleteNum())
                            .orElse(BigDecimal.ZERO));
            mesTaskService.update(new LambdaUpdateWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getCode, arrange.getTaskCode())
                    .set(MesTaskEntity::getStatus, MesTaskStatusEnum.FINISH.name())
                    .set(MesTaskEntity::getFinishTime, new Date())
                    .set(MesTaskEntity::getCompleteNum, completedQuantity));
            if (task != null && StrUtil.isNotBlank(task.getPlanNo())) {
                mesTaskService.feedbackPlanCompletion(task, completedQuantity);
            }
            if (task != null && "REWORK".equals(task.getResourceType())) {
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
        if (dto == null) {
            throw new CheckedException("设备开机参数不能为空");
        }
        MesDeviceGateDecision precheck = deviceGateService.checkDeviceStart(
                dto.getDeviceCode(), dto.getWorksiteCode(), null);
        if (!Boolean.TRUE.equals(precheck.getAllowed())) {
            throw new CheckedException("设备运行门阻断开机：" + precheck.getMessage());
        }
        MesWorksiteMoldEntity bind = worksiteMoldService.getOne(new LambdaQueryWrapper<MesWorksiteMoldEntity>()
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
                throw new CheckedException("换型开始后设备绑定工装已变化，必须取消当前换型并重新开始");
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
        changeoverExecutionMapper.lockDevice(dto.getDeviceCode().trim());
        MesChangeoverExecutionEntity byRequest = changeoverExecutionMapper.selectOne(
                new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
                        .eq(MesChangeoverExecutionEntity::getRequestId, dto.getRequestId()).last("limit 1"));
        if (byRequest != null) {
            if (!Objects.equals(byRequest.getDeviceCode(), dto.getDeviceCode())
                    || !Objects.equals(byRequest.getWorksiteCode(), dto.getWorksiteCode())) {
                throw new CheckedException("换型请求号已被其他设备使用");
            }
            return byRequest;
        }
        MesChangeoverExecutionEntity active = changeoverExecutionMapper.selectOne(
                new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
                        .eq(MesChangeoverExecutionEntity::getDeviceCode, dto.getDeviceCode())
                        .eq(MesChangeoverExecutionEntity::getStatus, "STARTED")
                        .orderByDesc(MesChangeoverExecutionEntity::getStartedTime).last("limit 1 FOR UPDATE"));
        if (active != null) {
            if (!Objects.equals(active.getWorksiteCode(), dto.getWorksiteCode())) {
                throw new CheckedException("设备已有其他工位发起的未完成换型，请先处理执行账[" + active.getId() + "]");
            }
            return active;
        }
        MesWorksiteMoldEntity bind = worksiteMoldService.getOne(new LambdaQueryWrapper<MesWorksiteMoldEntity>()
                .eq(MesWorksiteMoldEntity::getWorksiteCode, dto.getWorksiteCode())
                .eq(MesWorksiteMoldEntity::getDeviceCode, dto.getDeviceCode()).last("FOR UPDATE"));
        if (bind == null || StrUtil.isBlank(bind.getMoldCode())) {
            throw new CheckedException("当前工位与设备尚未绑定旧工装，不能开始换型");
        }
        MomUser user = SecurityUtils.getUser();
        MesChangeoverExecutionEntity row = new MesChangeoverExecutionEntity()
                .setRequestId(dto.getRequestId().trim()).setStatus("STARTED")
                .setWorksiteCode(dto.getWorksiteCode().trim()).setDeviceCode(dto.getDeviceCode().trim())
                .setFromToolingCode(bind.getMoldCode()).setFromToolingName(bind.getMoldName())
                .setStartedTime(LocalDateTime.now()).setStartedBy(user.getUsername()).setStartedByName(user.getName())
                .setSampleEligible(Boolean.FALSE).setExclusionReason("换型尚未完成")
                .setFactoryCode(bind.getFactoryCode());
        MdmDeviceEntity query = new MdmDeviceEntity();
        query.setDeviceCode(dto.getDeviceCode());
        R<MdmDeviceEntity> deviceResponse = deviceService.getDetails(query);
        if (deviceResponse == null || deviceResponse.getData() == null) {
            throw new CheckedException("无法读取设备主档，禁止开始无来源换型计时");
        }
        row.setDeviceName(deviceResponse.getData().getDeviceName());
        changeoverExecutionMapper.insert(row);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelChangeover(MesChangeoverCancelDTO dto) {
        if (dto == null || dto.getExecutionId() == null || StrUtil.isBlank(dto.getReason())) {
            throw new CheckedException("取消换型必须提供执行账ID和原因");
        }
        MesChangeoverExecutionEntity row = changeoverExecutionMapper.selectOne(
                new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
                        .eq(MesChangeoverExecutionEntity::getId, dto.getExecutionId()).last("FOR UPDATE"));
        if (row == null) throw new CheckedException("换型执行账不存在");
        if ("CANCELLED".equals(row.getStatus())) return;
        if (!"STARTED".equals(row.getStatus())) throw new CheckedException("只有进行中的换型可以取消");
        MomUser user = SecurityUtils.getUser();
        row.setStatus("CANCELLED").setCancelledTime(LocalDateTime.now())
                .setCancelledBy(user.getUsername()).setCancelledByName(user.getName())
                .setCancelReason(StrUtil.sub(dto.getReason().trim(), 0, 500))
                .setSampleEligible(Boolean.FALSE).setExclusionReason("换型已取消：" + dto.getReason().trim());
        changeoverExecutionMapper.updateById(row);
    }

    private MesTaskExecutionSegmentEntity openExecutionSegment(MesTaskArrangeEntity task, LocalDateTime now) {
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
                executionSegmentMapper.insert(segmentOf(task, no, task.getStartTime(), now, targetStatus,
                        "LEGACY_GAP", false, "升级前任务缺少连续时间段，不能作为校准样本"));
            }
            return;
        }
        active.setEndTime(now).setDurationSeconds(Math.max(0L, Duration.between(active.getStartTime(), now).getSeconds()))
                .setStatus(targetStatus).setCloseReason(reason);
        if ("CANCELLED".equals(targetStatus)) {
            active.setSampleEligible(Boolean.FALSE).setExclusionReason("派工任务未正常完工");
        }
        executionSegmentMapper.updateById(active);
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
                new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
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

    private void completeChangeover(DeviceCheckTaskStartDto dto, MesTaskDeviceRecordEntity deviceRecord,
                                    MdmDeviceEntity targetTooling) {
        MesChangeoverExecutionEntity row = requireStartedChangeover(dto);
        LocalDateTime completed = LocalDateTime.now();
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
