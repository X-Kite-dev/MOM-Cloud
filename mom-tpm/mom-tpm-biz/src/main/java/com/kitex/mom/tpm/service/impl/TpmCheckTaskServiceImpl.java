package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.constant.enums.YesNoEnum;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.dto.DeviceChangeMoldDto;
import com.kitex.mom.tpm.dto.DeviceCheckTaskStartDto;
import com.kitex.mom.tpm.dto.TpmCheckTaskDTO;
import com.kitex.mom.tpm.entity.*;
import com.kitex.mom.tpm.enums.*;
import com.kitex.mom.tpm.event.EventPublisherUtil;
import com.kitex.mom.tpm.mapper.TpmCheckTaskMapper;
import com.kitex.mom.tpm.mapper.TpmMaintenanceTaskMapper;
import com.kitex.mom.tpm.mapper.TpmRepairTaskMapper;
import com.kitex.mom.tpm.service.*;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 点/巡检任务
 *
 * @author kitex
 * @date 2025-02-18 17:49:05
 */
@Service
@AllArgsConstructor
public class TpmCheckTaskServiceImpl extends ServiceImpl<TpmCheckTaskMapper, TpmCheckTaskEntity> implements TpmCheckTaskService {

    private final TpmCheckTaskDetailsService tpmCheckTaskDetailsService;
    private final TpmCheckRecordService tpmCheckRecordService;
    private final RemoteDeviceService remoteDeviceService;
    private final TpmDeviceItemService tpmDeviceItemService;
    private final TpmCheckItemService tpmCheckItemService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final EventPublisherUtil eventPublisherUtil;
    private final TpmDeviceMoldRelaService moldRelaService;
    private final TpmPlanEventOutboxService tpmPlanEventOutboxService;
    private final TpmRepairTaskMapper tpmRepairTaskMapper;
    private final TpmMaintenanceTaskMapper tpmMaintenanceTaskMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /**
     * 获取任务明细
     *
     * @param id id
     * @return byId
     */
    @Override
    public TpmCheckTaskEntity getTaskById(Long id) {
        //查询主表
        TpmCheckTaskEntity byId = this.getById(id);
        require(byId != null, "点检任务不存在");
        //查询子表
        List<TpmCheckTaskDetailsEntity> detailsList = tpmCheckTaskDetailsService.list(new LambdaQueryWrapper<TpmCheckTaskDetailsEntity>()
                .eq(TpmCheckTaskDetailsEntity::getTaskCode, byId.getTaskCode()));
        byId.setDetailsList(detailsList);
        return byId;
    }

    /**
     * 任务处理
     *
     * @param tpmCheckTask 任务
     * @return 处理结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean handle(TpmCheckTaskEntity tpmCheckTask) {
        require(tpmCheckTask != null && tpmCheckTask.getId() != null, "点检任务ID不能为空");
        TpmCheckTaskEntity task = this.getOne(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                .eq(TpmCheckTaskEntity::getId, tpmCheckTask.getId()).last("FOR UPDATE"));
        require(task != null, "点检任务不存在");
        require(!CheckTaskStateEnum.HANDLE.getCode().equals(task.getState()), "点检任务已完成，禁止重复处理");
        require(!CheckTaskStateEnum.WAIT.getCode().equals(task.getState()), "点检任务尚未指派，不能处理");
        MomUser user = SecurityUtils.getUser();
        if (StrUtil.isNotBlank(task.getHandlePersonCode())) {
            require(Objects.equals(task.getHandlePersonCode(), user.getUsername()), "仅任务被指派人可以提交点检结果");
        }

        List<TpmCheckTaskDetailsEntity> storedDetails = tpmCheckTaskDetailsService.list(
                new LambdaQueryWrapper<TpmCheckTaskDetailsEntity>()
                        .eq(TpmCheckTaskDetailsEntity::getTaskCode, task.getTaskCode())
                        .orderByAsc(TpmCheckTaskDetailsEntity::getId));
        List<TpmCheckTaskDetailsEntity> submitted = tpmCheckTask.getDetailsList();
        require(CollUtil.isNotEmpty(storedDetails) && CollUtil.isNotEmpty(submitted), "点检任务明细不能为空");
        require(storedDetails.size() == submitted.size(), "点检任务明细数量与服务端记录不一致");
        Map<Long, TpmCheckTaskDetailsEntity> inputById = submitted.stream()
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(TpmCheckTaskDetailsEntity::getId, Function.identity(),
                        (left, right) -> {
                            throw new CheckedException("点检任务明细存在重复ID");
                        }));
        require(inputById.size() == storedDetails.size(), "点检任务明细ID不完整");
        for (TpmCheckTaskDetailsEntity detail : storedDetails) {
            TpmCheckTaskDetailsEntity input = inputById.get(detail.getId());
            require(input != null, "提交了不属于当前任务的点检明细");
            detail.setResult(StrUtil.trim(input.getResult()));
            detail.setRemark(StrUtil.trim(input.getRemark()));
            evaluate(detail);
        }

        long unfinished = storedDetails.stream().filter(item -> StrUtil.isBlank(item.getResult())).count();
        if (unfinished > 0) {
            if (unfinished < storedDetails.size()) task.setState(CheckTaskStateEnum.ING.getCode());
            require(tpmCheckTaskDetailsService.updateBatchById(storedDetails), "点检任务明细保存失败");
            require(this.updateById(task), "点检任务进度保存失败");
            return Boolean.TRUE;
        }

        LocalDateTime now = LocalDateTime.now();
        List<TpmCheckTaskDetailsEntity> failed = storedDetails.stream()
                .filter(item -> YesOrNoStateEnum.NO.getCode().equals(item.getJudgeResult())).toList();
        String highestAction = failed.stream().map(this::normalizedFailureAction)
                .max(Comparator.comparingInt(CheckFailureActionEnum::priority))
                .orElse(CheckFailureActionEnum.WARN.getCode());
        task.setJudgeResult(failed.isEmpty() ? YesOrNoStateEnum.YES.getCode() : YesOrNoStateEnum.NO.getCode());
        task.setFailureAction(failed.isEmpty() ? null : highestAction);
        task.setState(CheckTaskStateEnum.HANDLE.getCode());
        task.setHandleTime(now);
        MdmDeviceEntity device = getDevice(task.getDeviceCode());
        boolean productionBlocking = !failed.isEmpty() && !CheckFailureActionEnum.WARN.getCode().equals(highestAction);
        if (productionBlocking) {
            require(StrUtil.isNotBlank(tpmCheckTask.getBlockReason()), "存在封锁生产的不合格项，必须填写生产封锁原因");
            require(tpmCheckTask.getBlockReason().trim().length() <= 1000, "生产封锁原因不能超过1000字");
            LocalDateTime recovery = tpmCheckTask.getExpectedRecoveryTime();
            String forecastReason = StrUtil.trim(tpmCheckTask.getRecoveryForecastReason());
            Integer defaultMinutes = failed.stream()
                    .filter(item -> !CheckFailureActionEnum.WARN.getCode().equals(normalizedFailureAction(item)))
                    .map(TpmCheckTaskDetailsEntity::getDefaultRecoveryMinutes).filter(Objects::nonNull)
                    .filter(value -> value > 0).max(Integer::compareTo).orElse(null);
            if (recovery == null && defaultMinutes != null) {
                recovery = now.plusMinutes(defaultMinutes);
                forecastReason = "依据点检项目冻结的默认恢复时长" + defaultMinutes + "分钟自动推算";
            }
            if (recovery != null) {
                require(recovery.isAfter(now), "预计恢复时间必须晚于生产封锁时间");
                require(StrUtil.isNotBlank(forecastReason), "填写预计恢复时间时必须同时提供预测依据");
                require(forecastReason.trim().length() <= 500, "预计恢复预测依据不能超过500字");
            }
            task.setBlockStatus("BLOCKED");
            task.setBlockReason(tpmCheckTask.getBlockReason().trim());
            task.setBlockTime(now);
            task.setExpectedRecoveryTime(recovery);
            task.setRecoveryForecastReason(forecastReason);
            task.setBlockOperatorCode(user.getUsername());
            task.setBlockOperatorName(user.getName());
            task.setDeviceStateBeforeBlock(device.getDeviceState());
            task.setDeviceUsableBeforeBlock(device.getDeviceUsable());
            if (!List.of(DeviceStateEnum.REPAIR.getCode(), DeviceStateEnum.MAINTENANCE.getCode(), DeviceStateEnum.SCRAP.getCode())
                    .contains(device.getDeviceState())) {
                device.setDeviceState(DeviceStateEnum.PAUSE.getCode());
                device.setDeviceUsable(YesNoEnum.NO.getCode());
            }
        } else {
            task.setBlockStatus("NONE");
            // 开机点检只有“通过”或规则明确为“仅告警”的不合格才允许打开生产可用闸门。
            if (CheckTaskSourceEnum.POWER.getCode().equals(task.getSource())) {
                device.setDeviceState(DeviceStateEnum.RUNNING.getCode());
                device.setDeviceUsable(YesNoEnum.YES.getCode());
            }
        }
        require(tpmCheckTaskDetailsService.updateBatchById(storedDetails), "点检任务明细保存失败");
        require(this.updateById(task), "点检任务处理结果保存失败");
        TpmCheckRecordEntity checkRecord = new TpmCheckRecordEntity();
        BeanUtils.copyProperties(task, checkRecord, "id");
        require(tpmCheckRecordService.save(checkRecord), "点检记录保存失败");
        updateDevice(device, "点检任务处理后设备状态同步失败");
        if (productionBlocking) tpmPlanEventOutboxService.enqueueCheckUnavailable(task);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean releaseBlock(TpmCheckTaskDTO.ReleaseCommand command) {
        require(command != null && command.getTaskId() != null && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getReleaseReason()) && StrUtil.isNotBlank(command.getReleaseEvidence()),
                "封锁解除必须提供任务、幂等号、解除原因和证据");
        require(command.getRequestId().trim().length() <= 128, "解除请求幂等号不能超过128字");
        require(command.getReleaseReason().trim().length() <= 500, "解除原因不能超过500字");
        require(command.getReleaseEvidence().trim().length() <= 800, "解除证据不能超过800字");
        require(StrUtil.isBlank(command.getRelatedRepairTaskCode()) || command.getRelatedRepairTaskCode().trim().length() <= 100,
                "关联维修任务编码不能超过100字");
        TpmCheckTaskEntity task = this.getOne(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                .eq(TpmCheckTaskEntity::getId, command.getTaskId()).last("FOR UPDATE"));
        require(task != null, "点检任务不存在");
        if ("RELEASED".equals(task.getBlockStatus())) {
            require(Objects.equals(task.getReleaseRequestId(), command.getRequestId().trim())
                            && Objects.equals(task.getReleaseReason(), command.getReleaseReason().trim())
                            && Objects.equals(task.getReleaseEvidence(), command.getReleaseEvidence().trim())
                            && Objects.equals(task.getRelatedRepairTaskCode(), StrUtil.trim(command.getRelatedRepairTaskCode())),
                    "生产封锁已由其他请求解除，禁止使用不同内容重放");
            return Boolean.TRUE;
        }
        require("BLOCKED".equals(task.getBlockStatus()), "当前点检任务不存在待解除的生产封锁");
        MomUser user = SecurityUtils.getUser();
        require(!Objects.equals(task.getBlockOperatorCode(), user.getUsername()), "封锁发起人与解除人必须分离");
        String repairTaskCode = StrUtil.trim(command.getRelatedRepairTaskCode());
        if (CheckFailureActionEnum.REPAIR.getCode().equals(task.getFailureAction())) {
            require(StrUtil.isNotBlank(repairTaskCode), "转维修封锁必须关联已完成的维修任务");
            TpmRepairTaskEntity repair = tpmRepairTaskMapper.selectOne(new LambdaQueryWrapper<TpmRepairTaskEntity>()
                    .eq(TpmRepairTaskEntity::getTaskCode, repairTaskCode));
            require(repair != null && Objects.equals(repair.getDeviceCode(), task.getDeviceCode())
                    && Objects.equals(repair.getFactoryCode(), task.getFactoryCode()), "关联维修任务不存在、设备不一致或不属于当前工厂");
            require(RepairStateEnum.COMPLETE.getCode().equals(repair.getState()), "关联维修任务尚未完成，不能解除生产封锁");
        }
        LocalDateTime now = LocalDateTime.now();
        task.setBlockStatus("RELEASED");
        task.setReleaseRequestId(command.getRequestId().trim());
        task.setReleaseReason(command.getReleaseReason().trim());
        task.setReleaseEvidence(command.getReleaseEvidence().trim());
        task.setRelatedRepairTaskCode(repairTaskCode);
        task.setReleaseOperatorCode(user.getUsername());
        task.setReleaseOperatorName(user.getName());
        task.setReleasedTime(now);
        require(this.updateById(task), "点检生产封锁解除结果保存失败");
        tpmPlanEventOutboxService.markCheckRecovered(task, command.getReleaseEvidence().trim());

        if (!hasOtherActiveBlock(task) && !hasOpenRepairOrMaintenance(task.getDeviceCode())) {
            MdmDeviceEntity device = getDevice(task.getDeviceCode());
            if (DeviceStateEnum.PAUSE.getCode().equals(device.getDeviceState())) {
                String restoredState = task.getDeviceStateBeforeBlock();
                if (StrUtil.isBlank(restoredState) || DeviceStateEnum.PAUSE.getCode().equals(restoredState)) {
                    restoredState = CheckTaskSourceEnum.POWER.getCode().equals(task.getSource())
                            ? DeviceStateEnum.RUNNING.getCode() : DeviceStateEnum.IDLE.getCode();
                }
                device.setDeviceState(restoredState);
                device.setDeviceUsable(CheckTaskSourceEnum.POWER.getCode().equals(task.getSource())
                        ? YesNoEnum.YES.getCode() : StrUtil.blankToDefault(task.getDeviceUsableBeforeBlock(), YesNoEnum.YES.getCode()));
                updateDevice(device, "点检封锁解除后设备状态恢复失败");
            }
        }
        return Boolean.TRUE;
    }

    /**
     * 新增点检任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean addTask(String deviceCode) {
        MdmDeviceEntity data = getDevice(deviceCode);
        if (hasActiveCheckBlock(deviceCode)) {
            throw new CheckedException("设备存在未解除的点检生产封锁，不能创建新的开机点检绕过封锁");
        }
        List<TpmDeviceItemEntity> itemList = tpmDeviceItemService.list(new LambdaQueryWrapper<TpmDeviceItemEntity>()
                .eq(TpmDeviceItemEntity::getDeviceCode, deviceCode)
                .eq(TpmDeviceItemEntity::getType, CheckTypeEnum.SPOT.getCode()));
        List<Long> itemIds = itemList.stream().filter(item -> ObjectUtil.isNotEmpty(item.getItemId())).map(TpmDeviceItemEntity::getItemId).distinct().toList();
        List<TpmCheckItemEntity> tpmCheckItemEntities = new ArrayList<>();
        if (CollUtil.isNotEmpty(itemIds)) {
            tpmCheckItemEntities = tpmCheckItemService.listByIds(itemIds);
        }
        if (CollUtil.isEmpty(tpmCheckItemEntities)) {
            throw new RuntimeException("创建点检任务失败，请前往设备台账-编辑中维护点检项目！");
        }
        //查询设备点检任务，如果存在提示已创建点检任务
        List<TpmCheckTaskEntity> checkList = this.list(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                .eq(TpmCheckTaskEntity::getDeviceCode, deviceCode)
                .eq(TpmCheckTaskEntity::getSource, CheckTaskSourceEnum.POWER.getCode()));

        if (DeviceStateEnum.PAUSE.getCode().equals(data.getDeviceState()) && hasActiveCheckBlock(deviceCode)) {
            throw new CheckedException("设备存在未解除的点检生产封锁，不能通过历史点检记录重新开机");
        }


        long count = checkList.stream().filter(item -> !CheckTaskStateEnum.HANDLE.getCode().equals(item.getState())).count();
        if (count > 0L) {
            throw new RuntimeException("已创建点检任务，请等待点检完成！");
        }

        MomUser user = SecurityUtils.getUser();
        //创建开机记录
        eventPublisherUtil.publishDeviceLifeCycleEvent(entity -> {
            entity.setDeviceCode(data.getDeviceCode());
            entity.setDeviceName(data.getDeviceName());
            entity.setEventCode(null);
            entity.setOperator(user.getName() + "(" + user.getUsername() + ")");
            entity.setEventSource(DeviceLifeCycleEventEnum.POWER_ON.getCode());
            entity.setDescription("设备: " + data.getDeviceName() + "【" + data.getDeviceCode() + "】已开机");
        });

        //构建主表数据
        String taskCode = remoteSeqConfigService.create("device_check_task").getData();
        TpmCheckTaskEntity task = new TpmCheckTaskEntity();
        task.setTaskCode(taskCode);
        task.setType(CheckTypeEnum.SPOT.getCode());
        task.setSource(CheckTaskSourceEnum.POWER.getCode());
        task.setDeviceCode(data.getDeviceCode());
        task.setDeviceName(data.getDeviceName());
        task.setState(CheckTaskStateEnum.WAIT.getCode());
        //构建子表数据
        List<TpmCheckTaskDetailsEntity> detailsList = new ArrayList<>();
        for (TpmCheckItemEntity tpmCheckItemEntity : tpmCheckItemEntities) {
            TpmCheckTaskDetailsEntity checkTaskDetail = new TpmCheckTaskDetailsEntity();
            BeanUtils.copyProperties(tpmCheckItemEntity, checkTaskDetail, "id");
            checkTaskDetail.setTaskCode(taskCode);
            checkTaskDetail.setItemCode(tpmCheckItemEntity.getCode());
            checkTaskDetail.setItemName(tpmCheckItemEntity.getName());
            detailsList.add(checkTaskDetail);
        }
        tpmCheckTaskDetailsService.saveBatch(detailsList);
        return this.save(task);
    }

    /**
     * 批量指派人员
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean assign(List<Long> ids, String handlePersonCode, String handlePersonName) {
        require(CollUtil.isNotEmpty(ids) && ids.stream().allMatch(Objects::nonNull)
                        && StrUtil.isNotBlank(handlePersonCode) && StrUtil.isNotBlank(handlePersonName),
                "指派任务、处理人编码和名称不能为空");
        List<Long> distinctIds = ids.stream().distinct().toList();
        List<TpmCheckTaskEntity> checkTaskEntities = this.list(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                .in(TpmCheckTaskEntity::getId, distinctIds).orderByAsc(TpmCheckTaskEntity::getId).last("FOR UPDATE"));
        require(checkTaskEntities.size() == distinctIds.size(), "部分点检任务不存在或超出当前数据范围");
        require(checkTaskEntities.stream().allMatch(item -> CheckTaskStateEnum.WAIT.getCode().equals(item.getState())),
                "仅待指派点检任务允许指派处理人");
        checkTaskEntities.forEach(item -> {
            item.setState(CheckTaskStateEnum.ASSIGNED.getCode());
            item.setHandlePersonCode(handlePersonCode);
            item.setHandlePersonName(handlePersonName);
        });
        return this.updateBatchById(checkTaskEntities);
    }

    private MdmDeviceEntity getDevice(String deviceCode) {
        MdmDeviceEntity mdmDevice = new MdmDeviceEntity();
        mdmDevice.setDeviceCode(deviceCode);
        R<MdmDeviceEntity> data = remoteDeviceService.getDetails(mdmDevice);
        if (data == null || !Objects.equals(CommonConstants.SUCCESS, data.getCode())) {
            throw new RuntimeException("访问设备数据失败，请授权或者检查主数据服务是否运行！");
        }
        if (ObjectUtil.isEmpty(data.getData())) {
            throw new RuntimeException("查询设备为空！");
        }
        return data.getData();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(DeviceCheckTaskStartDto startDto) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", Boolean.FALSE);
        require(startDto != null && StrUtil.isNotBlank(startDto.getDeviceCode()), "设备开机参数不完整");
        MomUser user = SecurityUtils.getUser();
        MdmDeviceEntity device = getDevice(startDto.getDeviceCode());
        if (hasActiveCheckBlock(device.getDeviceCode())) {
            resultMap.put("msg", "设备存在未解除的点检生产封锁，不能开机！");
            return resultMap;
        }

        //处于保养中和维修中的设备不可开机
        if (DeviceStateEnum.MAINTENANCE.getCode().equals(device.getDeviceState())) {
//			throw new RuntimeException("设备处于保养状态不可开机！");
            resultMap.put("msg", "设备处于保养状态不可开机！");
            return resultMap;
        }
        if (DeviceStateEnum.REPAIR.getCode().equals(device.getDeviceState())) {
//			throw new RuntimeException("设备处于维修状态不可开机！");
            resultMap.put("msg", "设备处于维修状态不可开机！");
            return resultMap;
        }
        //如果处于开机状态，不走下面逻辑
        if (device.getDeviceState().equals(DeviceStateEnum.RUNNING.getCode())) {
//			return Boolean.TRUE;
            resultMap.put("data", Boolean.TRUE);
            return resultMap;
        }


        MdmDeviceEntity checkedMold = null;
        // 模具先点检，避免设备已经放行后才发现本次生产依赖的模具需要封锁。
        if (ObjectUtil.isNotEmpty(startDto.getMoldCode())) {
            List<TpmDeviceMoldRelaEntity> relations = moldRelaService.getListByDeviceCodes(List.of(device.getDeviceCode()));
            if (!relations.stream().map(TpmDeviceMoldRelaEntity::getMoldCode).toList().contains(startDto.getMoldCode())) {
                resultMap.put("msg", "设备与模具未设置绑定关系，开机失败！");
                return resultMap;
            }
            MdmDeviceEntity mold = getDevice(startDto.getMoldCode());
            if (hasActiveCheckBlock(mold.getDeviceCode())) {
                resultMap.put("msg", "模具存在未解除的点检生产封锁，不能开机！");
                return resultMap;
            }
            TpmCheckTaskEntity moldCheck = executeImmediateCheck(mold, startDto.getMoldDetailsList(), user);
            if ("BLOCKED".equals(moldCheck.getBlockStatus())) {
                resultMap.put("msg", "模具点检不合格，已按项目规则封锁并通知排产；请完成处置后由另一人员解除封锁");
                return resultMap;
            }
            // 保留原有“模具在机占用=不可再分配”语义；点检通过与资源占用是两个不同事实。
            mold.setDeviceState(DeviceStateEnum.RUNNING.getCode());
            mold.setDeviceUsable(YesNoEnum.NO.getCode());
            updateDevice(mold, "模具在机占用状态更新失败");
            checkedMold = mold;
        }
        TpmCheckTaskEntity deviceCheck = executeImmediateCheck(device, startDto.getDeviceDetailsList(), user);
        if ("BLOCKED".equals(deviceCheck.getBlockStatus())) {
            if (checkedMold != null) {
                checkedMold.setDeviceState(DeviceStateEnum.IDLE.getCode());
                checkedMold.setDeviceUsable(YesNoEnum.YES.getCode());
                updateDevice(checkedMold, "设备点检失败后模具占用释放失败");
            }
            resultMap.put("msg", "设备点检不合格，已按项目规则封锁并通知排产；请完成处置后由另一人员解除封锁");
            return resultMap;
        }
        if (checkedMold != null) publish(checkedMold, user);
        publish(device, user);
        resultMap.put("data", Boolean.TRUE);
        return resultMap;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean changeMold(DeviceChangeMoldDto moldDto) {
        require(moldDto != null && StrUtil.isNotBlank(moldDto.getOldMoldCode())
                && StrUtil.isNotBlank(moldDto.getNewMoldCode()), "换模参数不完整");
        MomUser user = SecurityUtils.getUser();
        MdmDeviceEntity newMold = getDevice(moldDto.getNewMoldCode());
        require(!hasActiveCheckBlock(newMold.getDeviceCode()), "新模具存在未解除的点检生产封锁");
        TpmCheckTaskEntity check = executeImmediateCheck(newMold, moldDto.getNewMoldDetailsList(), user);
        if ("BLOCKED".equals(check.getBlockStatus())) return Boolean.FALSE;
        newMold.setDeviceState(DeviceStateEnum.RUNNING.getCode());
        newMold.setDeviceUsable(YesNoEnum.NO.getCode());
        updateDevice(newMold, "新模具在机占用状态更新失败");

        MdmDeviceEntity oldMold = getDevice(moldDto.getOldMoldCode());
        oldMold.setDeviceState(DeviceStateEnum.IDLE.getCode());
        oldMold.setDeviceUsable(YesNoEnum.YES.getCode());
        updateDevice(oldMold, "旧模具状态更新失败");

        eventPublisherUtil.publishDeviceLifeCycleEvent(entity -> {
            entity.setDeviceCode(oldMold.getDeviceCode());
            entity.setDeviceName(oldMold.getDeviceName());
            entity.setEventCode(null);
            entity.setOperator(user.getName() + "(" + user.getUsername() + ")");
            entity.setEventSource(DeviceLifeCycleEventEnum.CLOSE.getCode());
            entity.setDescription("设备: " + oldMold.getDeviceName() + "【" + oldMold.getDeviceCode() + "】已关机");
        });

        publish(newMold, user);
        return Boolean.TRUE;
    }

    private void publish(MdmDeviceEntity device, MomUser user) {
        eventPublisherUtil.publishDeviceLifeCycleEvent(entity -> {
            entity.setDeviceCode(device.getDeviceCode());
            entity.setDeviceName(device.getDeviceName());
            entity.setEventCode(null);
            entity.setOperator(user.getName() + "(" + user.getUsername() + ")");
            entity.setEventSource(DeviceLifeCycleEventEnum.POWER_ON.getCode());
            entity.setDescription("设备: " + device.getDeviceName() + "【" + device.getDeviceCode() + "】已开机");
        });
    }

    /**
     * 现场开机/换模与后台任务共用同一套服务端判定，客户端只允许提交结果和备注。
     */
    private TpmCheckTaskEntity executeImmediateCheck(MdmDeviceEntity device,
                                                     List<TpmCheckTaskDetailsEntity> submitted,
                                                     MomUser user) {
        require(CollUtil.isNotEmpty(submitted), "设备[" + device.getDeviceCode() + "]点检结果不能为空");
        List<TpmDeviceItemEntity> relations = tpmDeviceItemService.list(new LambdaQueryWrapper<TpmDeviceItemEntity>()
                .eq(TpmDeviceItemEntity::getDeviceCode, device.getDeviceCode())
                .eq(TpmDeviceItemEntity::getType, CheckTypeEnum.SPOT.getCode()));
        List<Long> itemIds = relations.stream().map(TpmDeviceItemEntity::getItemId)
                .filter(Objects::nonNull).distinct().toList();
        require(CollUtil.isNotEmpty(itemIds), "设备[" + device.getDeviceCode() + "]未维护点检项目");
        List<TpmCheckItemEntity> items = tpmCheckItemService.listByIds(itemIds);
        Map<String, TpmCheckTaskDetailsEntity> inputByCode = submitted.stream()
                .filter(item -> StrUtil.isNotBlank(item.getItemCode()))
                .collect(Collectors.toMap(item -> item.getItemCode().trim(), Function.identity(),
                        (left, right) -> {
                            throw new CheckedException("现场点检结果存在重复项目编码");
                        }));
        require(items.size() == itemIds.size() && inputByCode.size() == items.size(),
                "现场点检结果与设备当前绑定项目不一致，请刷新后重新点检");
        String taskCode = remoteSeqConfigService.create("device_check_task").getData();
        require(StrUtil.isNotBlank(taskCode), "点检任务编码生成失败");
        TpmCheckTaskEntity task = new TpmCheckTaskEntity();
        task.setTaskCode(taskCode);
        task.setType(CheckTypeEnum.SPOT.getCode());
        task.setSource(CheckTaskSourceEnum.POWER.getCode());
        task.setDeviceCode(device.getDeviceCode());
        task.setDeviceName(device.getDeviceName());
        task.setState(CheckTaskStateEnum.ASSIGNED.getCode());
        task.setHandlePersonCode(user.getUsername());
        task.setHandlePersonName(user.getName());
        require(this.save(task), "现场点检任务保存失败");
        List<TpmCheckTaskDetailsEntity> details = new ArrayList<>();
        for (TpmCheckItemEntity item : items) {
            TpmCheckTaskDetailsEntity input = inputByCode.get(item.getCode());
            require(input != null && StrUtil.isNotBlank(input.getResult()),
                    "点检项目[" + item.getName() + "]尚未填写结果");
            TpmCheckTaskDetailsEntity detail = new TpmCheckTaskDetailsEntity();
            BeanUtils.copyProperties(item, detail, "id");
            detail.setTaskCode(taskCode);
            detail.setItemCode(item.getCode());
            detail.setItemName(item.getName());
            detail.setResult(input.getResult());
            detail.setRemark(input.getRemark());
            details.add(detail);
        }
        require(tpmCheckTaskDetailsService.saveBatch(details), "现场点检任务明细保存失败");
        task.setDetailsList(details);
        task.setBlockReason("开机/换模点检不合格，系统按点检项目冻结规则实施生产封锁");
        handle(task);
        return this.getById(task.getId());
    }

    private void evaluate(TpmCheckTaskDetailsEntity detail) {
        if (StrUtil.isBlank(detail.getResult())) {
            detail.setJudgeResult(null);
            return;
        }
        if (YesNoEnum.NO.getCode().equals(detail.getJudgeType())) {
            require(List.of(CheckJudgeTypeEnum.OK.getCode(), "NG").contains(detail.getResult()),
                    "点检项[" + detail.getItemName() + "]只能选择合格或不合格");
            detail.setJudgeResult(CheckJudgeTypeEnum.OK.getCode().equals(detail.getResult())
                    ? YesOrNoStateEnum.YES.getCode() : YesOrNoStateEnum.NO.getCode());
            return;
        }
        if (YesNoEnum.YES.getCode().equals(detail.getJudgeType())) {
            require(detail.getStandardMin() != null && detail.getStandardMax() != null,
                    "点检项[" + detail.getItemName() + "]缺少上下限标准");
            require(detail.getStandardMax().compareTo(detail.getStandardMin()) >= 0,
                    "点检项[" + detail.getItemName() + "]上下限标准无效");
            try {
                BigDecimal value = new BigDecimal(detail.getResult());
                detail.setJudgeResult(value.compareTo(detail.getStandardMax()) <= 0
                        && value.compareTo(detail.getStandardMin()) >= 0
                        ? YesOrNoStateEnum.YES.getCode() : YesOrNoStateEnum.NO.getCode());
            } catch (NumberFormatException ex) {
                throw new CheckedException("点检项[" + detail.getItemName() + "]的结果必须是合法数值");
            }
            return;
        }
        throw new CheckedException("点检项[" + detail.getItemName() + "]的判断类型不受支持");
    }

    private String normalizedFailureAction(TpmCheckTaskDetailsEntity detail) {
        return CheckFailureActionEnum.supports(detail.getFailureAction())
                ? detail.getFailureAction() : CheckFailureActionEnum.WARN.getCode();
    }

    private boolean hasActiveCheckBlock(String deviceCode) {
        return this.count(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                .eq(TpmCheckTaskEntity::getDeviceCode, deviceCode)
                .eq(TpmCheckTaskEntity::getBlockStatus, "BLOCKED")) > 0;
    }

    private boolean hasOtherActiveBlock(TpmCheckTaskEntity task) {
        return this.count(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                .eq(TpmCheckTaskEntity::getDeviceCode, task.getDeviceCode())
                .eq(TpmCheckTaskEntity::getBlockStatus, "BLOCKED")
                .ne(TpmCheckTaskEntity::getId, task.getId())) > 0;
    }

    private boolean hasOpenRepairOrMaintenance(String deviceCode) {
        long repairCount = tpmRepairTaskMapper.selectCount(new LambdaQueryWrapper<TpmRepairTaskEntity>()
                .eq(TpmRepairTaskEntity::getDeviceCode, deviceCode)
                .eq(TpmRepairTaskEntity::getState, RepairStateEnum.CREATE.getCode()));
        long maintenanceCount = tpmMaintenanceTaskMapper.selectCount(new LambdaQueryWrapper<TpmMaintenanceTaskEntity>()
                .eq(TpmMaintenanceTaskEntity::getDeviceCode, deviceCode)
                .eq(TpmMaintenanceTaskEntity::getState, MaintenanceTaskStateEnum.ING.getCode()));
        return repairCount > 0 || maintenanceCount > 0;
    }

    private void updateDevice(MdmDeviceEntity device, String message) {
        R<Boolean> response = remoteDeviceService.updateById(device);
        require(response != null && Objects.equals(CommonConstants.SUCCESS, response.getCode())
                && !Boolean.FALSE.equals(response.getData()), message);
    }


}
