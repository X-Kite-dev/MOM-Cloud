package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.mdm.feign.RemoteWorkCenterService;
import com.kitex.mom.mes.batch.BatchGenerator;
import com.kitex.mom.mes.batch.BatchGeneratorService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.MesTaskStatusEnum;
import com.kitex.mom.mes.enums.WorksiteTypeEnum;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.oms.enums.OrderLongStateEnum;
import com.kitex.mom.plan.feign.RemotePlnIssueService;
import com.kitex.mom.qms.api.feign.RemoteProcedureService;
import com.kitex.mom.qms.api.feign.RemoteQualityStandardService;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import com.kitex.mom.qms.enums.InspectionMethodEnum;
import com.kitex.mom.qms.enums.InspectionTaskTypeEnum;
import com.kitex.mom.qms.enums.ProcedureLabelTypeEnum;
import com.kitex.mom.tpm.api.feign.RemoteDeviceCheckService;
import com.kitex.mom.tpm.api.feign.RemoteTpmDeviceService;
import com.kitex.mom.tpm.dto.DeviceChangeMoldDto;
import com.kitex.mom.tpm.dto.DeviceCheckTaskStartDto;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final RemoteClassesService classesService;
    private final MesWarehouseFeedService feedService;
    private final RemoteWorkCenterService workCenterService;
    private final RemoteDeviceService deviceService;
    private final BatchGeneratorService batchGeneratorService;
    private final RemoteTpmDeviceService remoteTpmDeviceService;
    private final RemoteDeviceCheckService remoteDeviceCheckService;
    private final MesWorksiteMoldService worksiteMoldService;
    private final RemoteProcedureService remoteProcedureService;
    private final RemoteQualityStandardService remoteQualityStandardService;
    private final MesInspectionGenRecordService inspectionGenRecordService;
    private final MesInspectionRuleService inspectionRuleService;
    private final MesTaskDeviceRecordService deviceRecordService;
    private final RemotePlnIssueService remotePlnIssueService;
    private final RemoteParamService remoteParamService;

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
        List<String> produceTaskCodes = new ArrayList<>();
        List<MesTaskArrangeEntity> finalTaskArrangeList = new ArrayList<>();

        for (MesTaskArrangeEntity task : taskArrangeList) {
            if (StrUtil.isEmpty(task.getWorksiteCodes())) {
                throw new RuntimeException("未绑定工作中心，派工失败");
            }
            MesTaskArrangeEntity newTask = new MesTaskArrangeEntity();
            BeanUtil.copyProperties(task, newTask);

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
//                .apply("FIND_IN_SET({0}, worker_code) > 0", currentUser.getUsername())
                .eq("shift_code", shiftCode)
                .apply("FIND_IN_SET({0}, worksite_codes) > 0", worksiteCode)
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
        // 获取上一个工序合格数量
        MesProduceTaskEntity lastTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTaskCode, currentProduceTask.getTaskCode())
                .eq(MesProduceTaskEntity::getRouteOrder, currentProduceTask.getRouteOrder() - 1));
        if (Objects.nonNull(lastTask)) {
            result.setLastPassNum(lastTask.getCompleteNum());
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
        MesArrangeDeviceVO result = new MesArrangeDeviceVO();
        List<String> statusList = new ArrayList<>();
        statusList.add(MesProduceTaskStatusEnum.RECEIVE.name());
        statusList.add(MesProduceTaskStatusEnum.PRODUCT.name());
        statusList.add(MesProduceTaskStatusEnum.PAUSE.name());
        MomUser currentUser = SecurityUtils.getUser();
        MesTaskArrangeEntity arrange = this.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getWorkerCode, currentUser.getUsername())
                .eq(MesTaskArrangeEntity::getWorksiteCodes, worksiteCode)
                .eq(MesTaskArrangeEntity::getWorkshopCode, workshopCode)
                .eq(MesTaskArrangeEntity::getShiftCode, shiftCode)
                .in(MesTaskArrangeEntity::getStatus, statusList));
        if (Objects.isNull(arrange)) {
            return null;
        }
        BeanUtil.copyProperties(arrange, result);
        // TODO: 工作中心服务已废弃，需要重新实现根据工位查询设备的功能
        // 根据工作中心查询设备
        // MdmWorkCenterEntity workCenterEntity = workCenterService.detailsByCode(arrange.getWorksiteCodes()).getData();
        // if (Objects.nonNull(workCenterEntity)) {
        //     result.setDeviceCode(workCenterEntity.getDeviceCode());
        //     result.setDeviceName(workCenterEntity.getDeviceName());
        //
        //     R<List<TpmDeviceMoldRelaEntity>> listByDeviceCode = remoteTpmDeviceService.getListByDeviceCode(workCenterEntity.getDeviceCode());
        //     if (listByDeviceCode.getCode() != 0) {
        //         throw new CheckedException("获取设备关系备件信息失败,请检查设备服务是否运行！");
        //     }
        //     result.setDeviceMoldRela(listByDeviceCode.getData().size() > 0 ? YesOrNoStateEnum.YES.getCode() : YesOrNoStateEnum.NO.getCode());
        //
        //     MdmDeviceEntity queryParams = new MdmDeviceEntity();
        //     queryParams.setDeviceCode(workCenterEntity.getDeviceCode());
        //     MdmDeviceEntity device = deviceService.getDetails(queryParams).getData();
        //     if (Objects.nonNull(device)) {
        //         result.setDeviceUsable(device.getDeviceUsable());
        //         result.setDeviceStatus(device.getStatus());
        //         result.setDeviceState(device.getDeviceState());
        //         result.setDeviceClassCode(device.getClassCode());
        //         result.setDeviceClassName(device.getClassName());
        //         result.setDeviceLocation(device.getLocation());
        //         result.setDeviceId(device.getId());
        //         result.setDeviceOrgId(device.getOrgId());
        //     }
        // }
        return result;
    }

    /**
     * 获取当前设备
     *
     * @return
     */
    @Override
    public MesArrangeDeviceVO getRunningDevice(String shiftCode, String workshopCode, String worksiteCode) {
        MesArrangeDeviceVO result = new MesArrangeDeviceVO();
        // TODO: 工作中心服务已废弃，需要重新实现根据工位查询设备的功能
        // 根据工作中心查询设备
        // MdmWorkCenterEntity workCenterEntity = workCenterService.detailsByCode(worksiteCode).getData();
        // if (Objects.nonNull(workCenterEntity) && StrUtil.isNotEmpty(workCenterEntity.getDeviceCode())) {
        //     result.setDeviceCode(workCenterEntity.getDeviceCode());
        //     result.setDeviceName(workCenterEntity.getDeviceName());
        //
        //     R<List<TpmDeviceMoldRelaEntity>> listByDeviceCode = remoteTpmDeviceService.getListByDeviceCode(workCenterEntity.getDeviceCode());
        //     if (listByDeviceCode.getCode() != 0) {
        //         throw new CheckedException("获取设备关系备件信息失败,请检查设备服务是否运行！");
        //     }
        //     result.setDeviceMoldRela(!listByDeviceCode.getData().isEmpty() ? YesOrNoStateEnum.YES.getCode() : YesOrNoStateEnum.NO.getCode());
        //     List<String> moldCodes = listByDeviceCode.getData().stream().map(TpmDeviceMoldRelaEntity::getMoldCode).toList();
        //     List<MdmDeviceEntity> deviceList = deviceService.getMdmDeviceListByCodes(moldCodes).getData();
        //     List<MesUsableMoldDTO> moldList = new ArrayList<>();
        //     if (CollUtil.isNotEmpty(deviceList)) {
        //         deviceList.forEach(device -> moldList.add(new MesUsableMoldDTOEntity().setMoldCode(device.getDeviceCode()).setMoldName(device.getDeviceName())));
        //         result.setMoldList(moldList);
        //     }
        //
        //     MdmDeviceEntity queryParams = new MdmDeviceEntity();
        //     queryParams.setDeviceCode(workCenterEntity.getDeviceCode());
        //     MdmDeviceEntity device = deviceService.getDetails(queryParams).getData();
        //     if (Objects.nonNull(device)) {
        //         result.setDeviceUsable(device.getDeviceUsable());
        //         result.setDeviceStatus(device.getStatus());
        //         result.setDeviceState(device.getDeviceState());
        //         result.setDeviceClassCode(device.getClassCode());
        //         result.setDeviceClassName(device.getClassName());
        //         result.setDeviceLocation(device.getLocation());
        //         result.setDeviceId(device.getId());
        //         result.setDeviceOrgId(device.getOrgId());
        //     }
        // } else {
        //     throw new RuntimeException("未找到工作中心");
        // }

        // MesWorksiteMoldEntity worksiteMold = worksiteMoldService.getOne(new LambdaQueryWrapper<MesWorksiteMoldEntity>()
        //         .eq(MesWorksiteMoldEntity::getWorksiteCode, worksiteCode)
        //         .eq(MesWorksiteMoldEntity::getDeviceCode, workCenterEntity.getDeviceCode()));
        // if (Objects.nonNull(worksiteMold)) {
        //     result.setModelCode(worksiteMold.getMoldCode());
        //     result.setModelName(worksiteMold.getMoldName());
        // }

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
                        MesWarehouseFeedEntity::getMatCode, // 键
                        MesWarehouseFeedEntity::getFeedNum, // 值
                        BigDecimal::add // 合并逻辑
                ));
        Map<String, BigDecimal> usedMap = feedList.stream()
                .collect(Collectors.toMap(
                        MesWarehouseFeedEntity::getMatCode, // 键
                        entity -> Optional.ofNullable(entity.getUsedNum()).orElse(BigDecimal.ZERO), // 值，处理null
                        BigDecimal::add // 合并逻辑
                ));
        bomList.forEach(node -> calculateFeedAllNum(node, feedMap, usedMap));
        return bomList;
    }

    /**
     * 暂停
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pause(Long id) {
        this.update(new LambdaUpdateWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getId, id)
                .set(MesTaskArrangeEntity::getStatus, MesProduceTaskStatusEnum.PAUSE.name()));
    }

    /**
     * 接收
     *
     * @param id
     */
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

        boolean existTask = this.hasStartTask(task.getWorksiteCodes(), task.getWorkshopCode(), task.getShiftCode(), null);
        if (existTask) {
            throw new RuntimeException("同一工位，只能同时开始一个加工任务");
        }

        // 查询工作中心及对应的设备
//        String worksiteCode = task.getWorksiteCodes();
        // 查询当前正在运行设备
        MesArrangeDeviceVO runningDevice = getRunningDevice(null, null, worksiteCode);
        if (Objects.isNull(runningDevice) || "0".equals(runningDevice.getDeviceState()) || "5".equals(runningDevice.getDeviceState())) {
            throw new RuntimeException("设备尚未开机，请先进行设备开机");
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
        if (CollUtil.isNotEmpty(moldList) && StrUtil.isNotEmpty(runningDevice.getDeviceCode())) {
            // 查询工作中心-设备-模具绑定表 拿到唯一的绑定模具
            MesWorksiteMoldEntity worksiteMold = worksiteMoldService.getOne(new LambdaQueryWrapper<MesWorksiteMoldEntity>()
                    .eq(MesWorksiteMoldEntity::getWorksiteCode, worksiteCode)
                    .eq(MesWorksiteMoldEntity::getDeviceCode, runningDevice.getDeviceCode()));
            List<String> moldCodeList = moldList.stream().map(ProcessProcedureDTO.ProcessProcedureMoldDTO::getMoldCode).toList();
            // 如果绑定的模具在moldList中 则不需要换模
            if (!moldCodeList.contains(worksiteMold.getMoldCode())) {
                throw new RuntimeException("请进行换模后再次接收任务");
            }
        }

        task.setStatus(MesProduceTaskStatusEnum.RECEIVE.name());
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
        MesTaskArrangeEntity task = this.getById(id);
        if (Objects.isNull(task)) {
            throw new RuntimeException("加工任务不存在");
        }
        if (!(task.getStatus().equals(MesProduceTaskStatusEnum.RECEIVE.name())
                || task.getStatus().equals(MesProduceTaskStatusEnum.PAUSE.name()))) {
            throw new RuntimeException("只有已接收、已暂停任务才能开工");
        }

        boolean existTask = this.hasStartTask(task.getWorksiteCodes(), task.getWorkshopCode(), task.getShiftCode(), task.getId());
        if (existTask) {
            throw new RuntimeException("同一工位，只能同时开始一个加工任务");
        }

        // TODO 自动/手动 区别判断处理
        String type = task.getWorksiteTypes();
        if (WorksiteTypeEnum.AUTO.name().equals(type)) {

        }
        task.setStatus(MesProduceTaskStatusEnum.PRODUCT.name());
        LocalDateTime startDate = task.getStartTime();
        if (Objects.isNull(startDate)) {
            task.setStartTime(LocalDateTime.now());
        }

        this.updateById(task);


        // 更新工序任务
        MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getCode, task.getProduceTaskCode()));
        produceTask.setStatus(MesProduceTaskStatusEnum.PRODUCT.name());
        if (Objects.isNull(produceTask.getStartTime())) {
            produceTask.setStartTime(task.getStartTime());
        }
        produceTaskService.updateById(produceTask);
    }

    /**
     * 关闭
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(Long id) {
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
        MesTaskArrangeEntity arrange = this.getById(id);
        if (Objects.isNull(arrange)) {
            throw new RuntimeException("参数有误");
        }
        if (!arrange.getStatus().equals(MesProduceTaskStatusEnum.PRODUCT.name())) {
            throw new RuntimeException("只有加工中任务才能完工");
        }
        arrange.setFinishTime(LocalDateTime.now());
        arrange.setFinishHour(getFinishHour(arrange.getStartTime(), arrange.getFinishTime()));
        arrange.setStatus(MesProduceTaskStatusEnum.FINISH.name());
        this.updateById(arrange);

        // 更新工序计划表
        MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode()));

        produceTask.setStatus(MesProduceTaskStatusEnum.FINISH.name())
                .setFinishTime(LocalDateTime.now());

        produceTaskService.updateById(produceTask);

        MesTaskEntity mainTask = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()));

        // 如果工序设置了需要质检 则创建工序质检任务末检
        if ("1".equals(produceTask.getIsInspection())) {
            // 若设置了生成规则并且包含末检，则进行生成
            MesInspectionRuleEntity rule = inspectionRuleService.getRuleByMatCode(produceTask.getMatCode());
            if (Objects.nonNull(rule) && Arrays.asList(rule.getInspectionTypes().split(",")).contains(ProcedureLabelTypeEnum.LAST.getName())) {
                QmsQualityStandardEntity standard = remoteQualityStandardService.getRandomStandard(new QmsQualityStandardEntity()
                        .setMatCode(produceTask.getMatCode()).setInspectionType(InspectionTaskTypeEnum.PROCEDURE.name())).getData();
                if (Objects.isNull(standard)) {
                    throw new RuntimeException("该工序配置需要质检但未匹配到质检标准，请先维护质检标准");
                }
                String batchNo = "";
                BatchGenerator generator = batchGeneratorService.getBatchGenerator(mainTask.getBatchRuleCode());
                if (Objects.nonNull(generator)) {
                    // 获取当前班次
                    String currentShiftCode = classesService.getRuleBanci(LocalDateTime.now()).getData();
                    batchNo = generator.generateBatchNumber(produceTask.getTaskCode(), currentShiftCode);
                }
                InspectionProcedureCreateParam createParam = new InspectionProcedureCreateParam()
                        .setNum(produceTask.getNoticeNum().intValue())
                        .setTaskCode(produceTask.getCode())
                        .setBatchNo(batchNo)
                        .setMatCode(produceTask.getMatCode())
                        .setMatName(produceTask.getMatName())
                        .setProcessCode(produceTask.getRouteCode())
                        .setProcessName(produceTask.getRouteName())
                        .setStandardId(standard.getId())
                        .setLabelType(ProcedureLabelTypeEnum.LAST.getName());
                // 如果质检标准抽检方式为发起质检时确定 则获取系统配置的抽检个数
                if (InspectionMethodEnum.PENDING.name().equals(standard.getInspectionMethod())) {
                    String inspectionNumStr = remoteParamService.getByKey("QMS_DEFAULT_NUM").getData();
                    if (StrUtil.isEmpty(inspectionNumStr)) {
                        throw new RuntimeException("未配置抽检个数，请联系管理员");
                    }
                    createParam.setInspectionNum(Integer.parseInt(inspectionNumStr));
                }
                R<Long> LR = remoteProcedureService.create(createParam);
                Long taskId = LR.getData();
                // 处理创建结果
                if (Objects.isNull(taskId)) {
                    String msg = LR.getMsg();
                    if (msg != null && msg.contains("\"msg\":\"")) {
                        int start = msg.indexOf("\"msg\":\"");
                        int end = msg.indexOf("\"", start + 7);
                        msg = msg.substring(start + 7, end);
                    }
                    throw new RuntimeException(msg);
                } else {
                    // 保存生成记录
                    inspectionGenRecordService.save(new MesInspectionGenRecordEntity()
                            .setProduceTaskCode(produceTask.getCode())
                            .setTaskCode(produceTask.getTaskCode())
                            .setInspectionType(ProcedureLabelTypeEnum.LAST.getName())
                            .setInspectionId(taskId)
                            .setGenTime(LocalDateTime.now())
                            .setGenFlag("1")
                            .setGenRemark("生成成功")
                            .setFactoryCode(produceTask.getFactoryCode()));
                }
            }
        }

        // 最后一道工序
        if (Objects.nonNull(arrange.getLastRoute()) && arrange.getLastRoute()) {
            // 工序任务全部完成，则更新工单状态为已完成
            long count = produceTaskService.count(new LambdaUpdateWrapper<MesProduceTaskEntity>()
                    .eq(MesProduceTaskEntity::getCode, arrange.getProduceTaskCode())
                    .ne(MesProduceTaskEntity::getStatus, MesProduceTaskStatusEnum.FINISH.name()));
            if (count == 0L) {
                // 查询生产工单
                MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getCode, arrange.getTaskCode()));
                mesTaskService.update(new LambdaUpdateWrapper<MesTaskEntity>()
                        .eq(MesTaskEntity::getCode, arrange.getTaskCode())
                        .set(MesTaskEntity::getStatus, MesTaskStatusEnum.FINISH.name())
                        .set(MesTaskEntity::getFinishTime, new Date())
                        .set(MesTaskEntity::getCompleteNum, produceTask.getCompleteNum()));
                // todo 推送生产计划模块
                if (StrUtil.isNotBlank(task.getPlanNo())) {
                    planMesFinishUpdate(task, produceTask.getCompleteNum());
                }
            }
        }
    }

    /**
     * 生产计划模块修改对应的生产排产状态
     *
     * @param task 生产工单
     */
    private void planMesFinishUpdate(MesTaskEntity task, BigDecimal completeNum) {
        OmsOrderLifeCycleDTO omsOrderLifeCycleDTO = new OmsOrderLifeCycleDTO();
        omsOrderLifeCycleDTO.setNo(task.getPlanNo());
        omsOrderLifeCycleDTO.setProductCode(task.getMatCode());
        omsOrderLifeCycleDTO.setContent(String.format("工单编号【%s】,已完工,总生产数量【%s】,操作时间【%s】",
                task.getCode(), completeNum, LocalDate.now()));
        omsOrderLifeCycleDTO.setStatus(OrderLongStateEnum.SCHEDULE.getCode());
        omsOrderLifeCycleDTO.setType("mes");
        omsOrderLifeCycleDTO.setIssueStatus("3");
        omsOrderLifeCycleDTO.setFinishTime(LocalDate.now());
        Boolean statusUpdate = remotePlnIssueService.edit(Collections.singletonList(omsOrderLifeCycleDTO)).getData();
        if (statusUpdate == null || !statusUpdate) {
            throw new CheckedException(String.format("%s的工单推送生产计划模块失败", task.getCode()));
        }
    }

    /**
     * 设备开机/换模
     *
     * @param dto
     */
    @Override
    public void deviceStart(DeviceCheckTaskStartDto dto) {
        MesWorksiteMoldEntity bind = worksiteMoldService.getOne(new LambdaQueryWrapper<MesWorksiteMoldEntity>()
                .eq(MesWorksiteMoldEntity::getWorksiteCode, dto.getWorksiteCode())
                .eq(MesWorksiteMoldEntity::getDeviceCode, dto.getDeviceCode()));
        MomUser user = SecurityUtils.getUser();
        String content = "";
        // 换模
        if (dto.getChangeMold()) {
            if (StrUtil.isEmpty(dto.getMoldCode())) {
                throw new RuntimeException("请扫描换模模具");
            }
            String oldMoldCode = bind.getMoldCode();
            if (oldMoldCode.equals(dto.getMoldCode())) {
                throw new RuntimeException("已绑定此模具" + dto.getMoldCode() + "，无需换模");
            }
            // 设备换模
            remoteDeviceCheckService.changeMold(new DeviceChangeMoldDto()
                    .setOldMoldCode(oldMoldCode).setNewMoldCode(dto.getMoldCode())
                    .setNewMoldDetailsList(dto.getMoldDetailsList()));
            content = user.getName() + "于" + DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "进行换模，模具编码为：" + dto.getMoldCode();
            bind.setMoldCode(dto.getMoldCode());
            MdmDeviceEntity queryEntity = new MdmDeviceEntity();
            queryEntity.setDeviceCode(dto.getMoldCode());
            MdmDeviceEntity moldDetail = deviceService.getDetails(queryEntity).getData();
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
            MdmDeviceEntity queryEntity = new MdmDeviceEntity();
            queryEntity.setDeviceCode(dto.getMoldCode());
            MdmDeviceEntity moldDetail = deviceService.getDetails(queryEntity).getData();
            if (Objects.nonNull(moldDetail)) {
                bind.setMoldName(moldDetail.getDeviceName());
            }
            // 设备开机
            Map<String, Object> startResult = remoteDeviceCheckService.start(dto).getData();
            if (!(boolean) startResult.get("data")) {
                throw new RuntimeException(startResult.get("msg").toString());
            }
            content = user.getName() + "于" + DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + "进行设备开机，设备编码为：" + dto.getDeviceCode() + "模具编码为：" + dto.getMoldCode();
        }

        worksiteMoldService.saveOrUpdate(bind);

        // 保存开机/换模记录
        MdmDeviceEntity deviceQuery = new MdmDeviceEntity();
        deviceQuery.setDeviceCode(dto.getDeviceCode());
        MdmDeviceEntity deviceEntity = deviceService.getDetails(deviceQuery).getData();
        MdmDeviceEntity moldQuery = new MdmDeviceEntity();
        moldQuery.setDeviceCode(dto.getMoldCode());
        MdmDeviceEntity moldEntity = deviceService.getDetails(moldQuery).getData();
        deviceRecordService.save(new MesTaskDeviceRecordEntity()
                .setDeviceCode(dto.getDeviceCode())
                .setDeviceName(Objects.nonNull(deviceEntity) ? deviceEntity.getDeviceName() : "")
                .setMoldCode(dto.getMoldCode())
                .setMoldName(Objects.nonNull(moldEntity) ? moldEntity.getDeviceName() : "")
                .setDeviceDetails(JSONUtil.toJsonStr(dto.getDeviceDetailsList()))
                .setMoldDetails(JSONUtil.toJsonStr(dto.getMoldDetailsList()))
                .setWorksiteCode(dto.getWorksiteCode())
                .setChangeMold(dto.getChangeMold())
                .setContent(content));

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
                .eq(MesTaskArrangeEntity::getWorkerCode, SecurityUtils.getUser().getUsername())
                .ne(Objects.nonNull(taskId), MesTaskArrangeEntity::getId, taskId)
                .eq(MesTaskArrangeEntity::getShiftCode, shiftCode)
                .eq(MesTaskArrangeEntity::getWorksiteCodes, worksiteCode)
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
