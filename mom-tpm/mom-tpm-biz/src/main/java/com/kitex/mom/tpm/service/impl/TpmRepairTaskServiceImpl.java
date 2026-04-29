package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.enums.DeviceUsableEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.delayedConfig.TpmDelayedConfig;
import com.kitex.mom.tpm.delayedConfig.TpmMessageDelayed;
import com.kitex.mom.tpm.delayedConfig.TpmNoticePerson;
import com.kitex.mom.tpm.delayedConfig.TpmTaskNotice;
import com.kitex.mom.tpm.entity.*;
import com.kitex.mom.tpm.enums.*;
import com.kitex.mom.tpm.mapper.*;
import com.kitex.mom.tpm.service.*;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.DelayQueue;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 维修任务表
 *
 * @author kitex
 * @date 2025-02-06 16:06:41
 */
@Service
@AllArgsConstructor
public class TpmRepairTaskServiceImpl extends ServiceImpl<TpmRepairTaskMapper, TpmRepairTaskEntity> implements TpmRepairTaskService {

    public static final String TPMDELAY = "TPMDELAY";
    public static String MESSAGE = "维修任务编码为【%s】的维修任务已超出规定完成时间!    ";

    private final TpmRepairTaskDetailsService tpmRepairTaskDetailsService;
    private final TpmRepairRemindMapper tpmRepairRemindMapper;
    private final TpmRepairRemindDetailsMapper tpmRepairRemindDetailsMapper;
    private final TpmDelayedConfig tpmDelayedConfig;
    private final RemoteDeviceService remoteDeviceService;
    private final RemoteMessagePushService remoteMessagePushService;
    private final RemoteUserService remoteUserService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final TpmMaintenanceTaskMapper tpmMaintenanceTaskMapper;
    private final TpmRepairRecordMapper tpmRepairRecordMapper;
    private final TpmSparePartsInOrOutRecordService tpmSparePartsInOrOutRecordService;
    private final TpmSparePartsInOrOutBillService tpmSparePartsInOrOutBillService;
    private final TpmSparePartsStockService tpmSparePartsStockService;
    private final TpmSparePartsService tpmSparePartsService;
    private final TpmSparePartsApplicationService tpmSparePartsApplicationService;


    @Override
    public TpmRepairTaskEntity getTaskById(Long id) {
        //查询主表
        TpmRepairTaskEntity byId = this.getById(id);
        //查询子表
        List<TpmRepairTaskDetailsEntity> detailsList = tpmRepairTaskDetailsService.list(new LambdaQueryWrapper<TpmRepairTaskDetailsEntity>()
                .eq(TpmRepairTaskDetailsEntity::getTaskCode, byId.getTaskCode()));
        byId.setDetailsList(detailsList);
        return byId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveTask(TpmRepairTaskEntity tpmRepairTask) {
        LocalDateTime now = LocalDateTime.now();
        tpmRepairTask.setType(RepairTaskTypeEnum.INTERNAL.getCode());
        if (!tpmRepairTask.getRemindSign()) {
            //保存任务
            MomUser user = SecurityUtils.getUser();
            tpmRepairTask.setSubmitBy(user.getUsername());
            tpmRepairTask.setSubmitByName(user.getName());
            tpmRepairTask.setSubmitTime(now);
            tpmRepairTask.setState(RepairStateEnum.CREATE.getCode());
            this.save(tpmRepairTask);
        } else {
            //根据班次、设备、故障类型查询维修配置
            List<TpmRepairRemindEntity> targetRemindList = tpmRepairRemindMapper.getTarget(tpmRepairTask.getFaultClassId(), tpmRepairTask.getDeviceCode(), tpmRepairTask.getShiftCode());
            if (CollUtil.isEmpty(targetRemindList)) {
                throw new RuntimeException("根据故障类型、设备、班次未查询到相关的维修配置信息，请先维护！");
            }
            //查询设备
            MdmDeviceEntity mdmDevice = new MdmDeviceEntity();
            mdmDevice.setDeviceCode(tpmRepairTask.getDeviceCode());
            MdmDeviceEntity data = remoteDeviceService.getDetails(mdmDevice).getData();
            if (ObjectUtil.isEmpty(data)) {
                throw new RuntimeException("访问设备数据失败，请授权或者检查主数据服务是否运行！");
            }

            if (data.getDeviceState().equals(DeviceStateEnum.RUNNING.getCode())
                    || data.getDeviceState().equals(DeviceStateEnum.PAUSE.getCode())) {
                throw new RuntimeException("新增失败，设备处于生产状态，请先将设备停止运行！");
            }

            //保存任务
            MomUser user = SecurityUtils.getUser();
            tpmRepairTask.setSubmitBy(user.getUsername());
            tpmRepairTask.setSubmitByName(user.getName());
            tpmRepairTask.setSubmitTime(now);
            tpmRepairTask.setState(RepairStateEnum.CREATE.getCode());


            //查询相关人配置信息,发送消息提醒
            List<String> remindCodes = targetRemindList.stream().map(TpmRepairRemindEntity::getRemindCode).distinct().toList();
            List<TpmRepairRemindDetailsEntity> tpmRepairRemindDetailsList = tpmRepairRemindDetailsMapper.selectList(new LambdaQueryWrapper<TpmRepairRemindDetailsEntity>()
                    .in(TpmRepairRemindDetailsEntity::getRemindCode, remindCodes));
            try {
                messageHandle(tpmRepairRemindDetailsList, tpmRepairTask);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage());
            }

            this.save(tpmRepairTask);
            //更改设备状态
            data.setDeviceState(DeviceStateEnum.REPAIR.getCode());
            data.setDeviceUsable(DeviceUsableEnum.NO.getCode());
            remoteDeviceService.updateById(data).getData();
        }
        return Boolean.TRUE;
    }

    /**
     * 添加发送消息到队列
     */
    private void messageHandle(List<TpmRepairRemindDetailsEntity> tpmRepairRemindDetailsList, TpmRepairTaskEntity tpmRepairTask) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        LocalDateTime now = LocalDateTime.now();
        DelayQueue<TpmMessageDelayed> queue = tpmDelayedConfig.getQueue();
        List<TpmMessageDelayed> results = new ArrayList<>();
        //处理人立即通知
        Set<Long> userIdsList = new HashSet<>();
        List<TpmRepairRemindDetailsEntity> handlePersonList = tpmRepairRemindDetailsList.stream().filter(item -> RepairPersonTypeEnum.HANDLE.getCode().equals(item.getPersonType())).toList();
        for (TpmRepairRemindDetailsEntity remindDetails : handlePersonList) {
            String userIds = remindDetails.getUserIds();
            userIdsList.addAll(Arrays.stream(userIds.split(",")).map(Long::valueOf).toList());
        }
        List<SysUserEntity> userList = remoteUserService.getUserByIdInner(userIdsList.stream().toList()).getData();
        if (CollUtil.isEmpty(userList)) {
            throw new RuntimeException("查询用户数据失败！");
        }
        List<String> usernameList = userList.stream().map(SysUserEntity::getUsername).toList();
        List<String> nameList = userList.stream().map(SysUserEntity::getName).toList();
        tpmRepairTask.setRepairBy(String.join(",", usernameList));
        tpmRepairTask.setRepairByName(String.join(",", nameList));
        String userIds = userList.stream().map(SysUserEntity::getWxDingUserid).collect(Collectors.joining(","));
        //构建消息体
        String message = String.format(MESSAGE, tpmRepairTask.getTaskCode(), now.format(formatter));
        //构建参数推送消息
        MessageDTO messageDTO = new MessageDTO();
        messageDTO.setCode(TPMDELAY);
        messageDTO.setMessageType(MessageTypeEnum.TEXT.getType());
        messageDTO.setUserList(userIdsList.stream().toList());
        messageDTO.setUserIds(userIds);
        messageDTO.setMessage(message);
        remoteMessagePushService.pushPersonMsg(messageDTO);

        //通知人消息通知
        List<TpmNoticePerson> noticePersonList = new ArrayList<>();
        Map<String, List<TpmRepairRemindDetailsEntity>> remindMap = tpmRepairRemindDetailsList.stream().collect(Collectors.groupingBy(TpmRepairRemindDetailsEntity::getRemindCode, Collectors.toList()));

        for (Map.Entry<String, List<TpmRepairRemindDetailsEntity>> entry : remindMap.entrySet()) {
            List<TpmRepairRemindDetailsEntity> value = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(TpmRepairRemindDetailsEntity::getUserLevel)).toList();
            //获取处理人处理时间
            TpmRepairRemindDetailsEntity handlePerson = value.get(0);
            Integer expireTime = getExpireTime(handlePerson.getHandleUnit(), handlePerson.getHandleLimit());
            //从第二个开始 ，第一个为处理人
            for (int i = 1; i < value.size(); i++) {
                TpmRepairRemindDetailsEntity remindDetails = value.get(i);
                for (String userId : remindDetails.getUserIds().split(",")) {
                    TpmNoticePerson noticePerson = new TpmNoticePerson(Long.valueOf(userId), expireTime);
                    noticePersonList.add(noticePerson);
                }
                if (ObjectUtil.isNotEmpty(remindDetails.getHandleLimit())
                        && ObjectUtil.isNotEmpty(remindDetails.getHandleUnit())) {
                    Integer time = getExpireTime(remindDetails.getHandleUnit(), remindDetails.getHandleLimit());
                    expireTime = expireTime + time;
                }
            }
        }
        //通知结果值 noticePersonList
        Map<Integer, List<TpmNoticePerson>> noticePersonMap = noticePersonList.stream().collect(Collectors.groupingBy(TpmNoticePerson::getExpireTime, Collectors.toList()));
        //统一时间值去重用户 添加到延时队列
        for (Map.Entry<Integer, List<TpmNoticePerson>> noticePerson : noticePersonMap.entrySet()) {
            List<Long> userIdList = noticePerson.getValue().stream().map(TpmNoticePerson::getUserId).distinct().toList();
            results.add(buildTpmMessageDelayed(tpmRepairTask.getId(), userIdList, Long.valueOf(noticePerson.getKey())));
        }
        queue.addAll(results);
    }


    private TpmMessageDelayed buildTpmMessageDelayed(Long id, List<Long> userIdList, Long expireTime) {
        TpmTaskNotice taskNotice = new TpmTaskNotice();
        taskNotice.setId(id);
        taskNotice.setUserIdList(userIdList);
        return new TpmMessageDelayed(taskNotice, expireTime);
    }


    private Integer getExpireTime(String handleUnit, Integer handleLimit) {
        Integer results = null;
        if (TimeUnitEnum.MINUTE.getCode().equals(handleUnit)) {
            results = handleLimit;
        }
        if (TimeUnitEnum.HOUR.getCode().equals(handleUnit)) {
            results = handleLimit * 60;
        }
        if (TimeUnitEnum.DAY.getCode().equals(handleUnit)) {
            results = handleLimit * 60 * 24;
        }
        return results;
    }


    /**
     * 处理维修任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean handleTask(TpmRepairTaskEntity tpmRepairTask) {
        //查询设备，更改设备状态
        MdmDeviceEntity mdmDevice = new MdmDeviceEntity();
        mdmDevice.setDeviceCode(tpmRepairTask.getDeviceCode());
        MdmDeviceEntity data = remoteDeviceService.getDetails(mdmDevice).getData();
        if (ObjectUtil.isEmpty(data)) {
            throw new RuntimeException("访问设备数据失败，请授权或者检查主数据服务是否运行！");
        }
        List<TpmMaintenanceTaskEntity> maintenanceTaskList = tpmMaintenanceTaskMapper.selectList(new LambdaQueryWrapper<TpmMaintenanceTaskEntity>()
                .eq(TpmMaintenanceTaskEntity::getDeviceCode, tpmRepairTask.getDeviceCode())
                .eq(TpmMaintenanceTaskEntity::getState, MaintenanceTaskStateEnum.ING.getCode()));
        if (CollUtil.isEmpty(maintenanceTaskList)) {
            data.setDeviceState(DeviceStateEnum.IDLE.getCode());
            data.setDeviceUsable(DeviceUsableEnum.YES.getCode());
        } else {
            data.setDeviceState(DeviceStateEnum.MAINTENANCE.getCode());
            data.setDeviceUsable(DeviceUsableEnum.NO.getCode());
        }
        //保存主表
        LocalDateTime now = LocalDateTime.now();
        MomUser user = SecurityUtils.getUser();
        tpmRepairTask.setRepairBy(user.getUsername());
        tpmRepairTask.setRepairByName(user.getName());
        tpmRepairTask.setRepairTime(now);
        tpmRepairTask.setState(RepairStateEnum.COMPLETE.getCode());
        this.updateById(tpmRepairTask);
        //保存明细
        List<TpmRepairTaskDetailsEntity> detailsList = tpmRepairTask.getDetailsList();
        if (CollUtil.isNotEmpty(detailsList)) {
            //逻辑变更
            //1.保存维修备件使用明细
            detailsList.forEach(item -> {
                item.setId(null);
                item.setUseTime(now);
                item.setTaskCode(tpmRepairTask.getTaskCode());
            });
            tpmRepairTaskDetailsService.saveBatch(detailsList);

            //2.查询备件申请数据
            List<TpmSparePartsApplicationDetailsEntity> applyBySourceCode = tpmSparePartsApplicationService.getApplyBySourceCode(tpmRepairTask.getTaskCode(), SparePartsApplicationSourceEnum.FAULT.getCode());
            List<TpmSparePartsApplicationDetailsEntity> passApplicationDetailsList = applyBySourceCode.stream().filter(item -> item.getApprovalStatus()
                    .equals(SparePartsApplicationStateEnum.PASS.getCode())).toList();

            Map<String, TpmSparePartsApplicationDetailsEntity> applicationSparePartsMap = applyBySourceCode.stream().collect(Collectors.toMap(TpmSparePartsApplicationDetailsEntity::getSparePartCode, Function.identity(), (k1, k2) -> k1));

            Map<String, BigDecimal> applicationMap = passApplicationDetailsList.stream().collect(Collectors.groupingBy(
                    TpmSparePartsApplicationDetailsEntity::getSparePartCode,
                    Collectors.reducing(BigDecimal.ZERO, TpmSparePartsApplicationDetailsEntity::getQuantityRequested, BigDecimal::add)));

            //3.比对备件申请通过的备件数与备件使用的数据，如果备件没有全部使用，自动生成备件退回单据
            List<TpmSparePartsApplicationDetailsEntity> returnList = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : applicationMap.entrySet()) {
                TpmRepairTaskDetailsEntity entity = detailsList.stream().filter(item -> item.getSparePartsCode().equals(entry.getKey())).findFirst().orElse(null);
                if (ObjectUtil.isEmpty(entity)) {
                    TpmSparePartsApplicationDetailsEntity item = applicationSparePartsMap.get(entry.getKey());
                    returnList.add(buildApply(item, entry.getValue()));
                } else {
                    if (entry.getValue().compareTo(entity.getUseNum()) > 0) {
                        TpmSparePartsApplicationDetailsEntity item = applicationSparePartsMap.get(entry.getKey());
                        BigDecimal subtract = entry.getValue().subtract(entity.getUseNum());
                        returnList.add(buildApply(item, subtract));
                    }
                }

            }
            if (CollUtil.isNotEmpty(returnList)) {
                String applyCode = remoteSeqConfigService.create("spare_parts_application").getData();
                returnList.forEach(item -> {
                    item.setApplyCode(applyCode);
                });
                TpmSparePartsApplicationEntity application = new TpmSparePartsApplicationEntity();
                application.setApplyCode(applyCode);
                application.setType(BillOperateTypeEnum.IN.getCode());
                application.setSourceCode(tpmRepairTask.getTaskCode());
                application.setSource(SparePartsApplicationSourceEnum.FAULT_RETURN.getCode());
                application.setRequesterCode(user.getUsername());
                application.setRequesterName(user.getName());
                application.setRequestDate(LocalDate.now());
                application.setApprovalStatus(SparePartsApplicationStateEnum.WAIT.getCode());
                application.setDetailsList(returnList);
                //保存备件退回申请
                tpmSparePartsApplicationService.addApply(application);

            }

        }
        //生成维修记录
        TpmRepairRecordEntity repairRecord = new TpmRepairRecordEntity();
        BeanUtils.copyProperties(tpmRepairTask, repairRecord, "id");
        tpmRepairRecordMapper.insert(repairRecord);
        //保存备件信息

        //保存设备状态
        remoteDeviceService.updateById(data).getData();
        return Boolean.TRUE;
    }

    private TpmSparePartsApplicationDetailsEntity buildApply(TpmSparePartsApplicationDetailsEntity item, BigDecimal quantityRequested) {
        TpmSparePartsApplicationDetailsEntity entity = new TpmSparePartsApplicationDetailsEntity();
        entity.setSparePartCode(item.getSparePartCode());
        entity.setSparePartName(item.getSparePartName());
        entity.setSpecification(item.getSpecification());
        entity.setUnit(item.getUnit());
        entity.setQuantityRequested(quantityRequested);
        return entity;
    }


}