package com.kitex.mom.tpm.task;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.admin.api.dto.UserInfo;
import com.kitex.admin.api.feign.RemoteMessageService;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.admin.api.vo.OrgTreeVO;
import com.kitex.admin.api.vo.SysMessageVO;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import com.kitex.mom.tpm.entity.TpmMaintenancePlanDetailsEntity;
import com.kitex.mom.tpm.entity.TpmMaintenancePlanEntity;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskDetailsEntity;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskEntity;
import com.kitex.mom.tpm.enums.*;
import com.kitex.mom.tpm.service.TpmMaintenancePlanDetailsService;
import com.kitex.mom.tpm.service.TpmMaintenancePlanService;
import com.kitex.mom.tpm.service.TpmMaintenanceTaskDetailsService;
import com.kitex.mom.tpm.service.TpmMaintenanceTaskService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Slf4j
@Component
@RequiredArgsConstructor
public class TpmMaintenancePlanTask {

    public static final String TPMDELAY = "TPMDELAY";

    public static final String MESSAGE = "您有一条新的保养任务将于%s生成，请注意查收！";

    private final TpmMaintenancePlanService tpmMaintenancePlanService;
    private final TpmMaintenancePlanDetailsService tpmMaintenancePlanDetailsService;
    private final TpmMaintenanceTaskService tpmMaintenanceTaskService;
    private final TpmMaintenanceTaskDetailsService tpmMaintenanceTaskDetailsService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final RemoteMessagePushService remoteMessagePushService;
    private final RemoteUserService remoteUserService;
    private final RemoteMessageService remoteMessageService;

    /**
     * 保养计划 定时任务生成保养任务
     */
    @XxlJob("device_maintenance")
//	@Scheduled(cron = "0 * * * * ? ")
    public void deviceMaintenance() {
        log.info("设备保养定时任务 start==>");
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        List<TpmMaintenancePlanEntity> updatePlanList = new ArrayList<>();
        List<TpmMaintenanceTaskEntity> addTaskList = new ArrayList<>();
        List<TpmMaintenanceTaskDetailsEntity> addTaskDetailsList = new ArrayList<>();
        //数据准备 ===>查询现有的计划和计划明细
        //查询所有执行中的保养计划
        List<TpmMaintenancePlanEntity> planList = tpmMaintenancePlanService.list(new LambdaQueryWrapper<TpmMaintenancePlanEntity>()
                .eq(TpmMaintenancePlanEntity::getExecuteState, PlanExecuteStateEnum.ING.getCode()));
        //查询所有保养计划明细，过滤需要的保养计划明细 ，不使用in,转成Map结构
        List<TpmMaintenancePlanDetailsEntity> planDetailsList = tpmMaintenancePlanDetailsService.listAllInfos();
        List<Long> planIds = planList.stream().map(TpmMaintenancePlanEntity::getId).toList();
        planDetailsList = planDetailsList.stream().filter(item -> planIds.contains(item.getPlanId())).toList();
        Map<Long, List<TpmMaintenancePlanDetailsEntity>> planDetailsMap = planDetailsList.stream().collect(Collectors.groupingBy(TpmMaintenancePlanDetailsEntity::getPlanId, Collectors.toList()));
        //一次性任务
        List<TpmMaintenancePlanEntity> onceList = planList.stream().filter(item -> MaintenancePlanTypeEnum.ONCE.getCode().equals(item.getType())).toList();
        oncePlanHandle(onceList, planDetailsMap, now, updatePlanList, addTaskList, addTaskDetailsList);

        //周期性任务
        List<TpmMaintenancePlanEntity> cycleList = planList.stream().filter(item -> MaintenancePlanTypeEnum.CYCLE.getCode().equals(item.getType())).toList();
        cyclePlanHandle(cycleList, planDetailsMap, now, updatePlanList, addTaskList, addTaskDetailsList);

        tpmMaintenancePlanService.updateBatchById(updatePlanList);
        tpmMaintenanceTaskService.saveBatch(addTaskList);
        tpmMaintenanceTaskDetailsService.saveBatch(addTaskDetailsList);
        log.info("设备保养定时任务 ==>end");
    }


    /**
     * 获取下一次执行时间
     *
     * @param lastTime       上次执行时间
     * @param circleTimeUnit 周期单位
     * @param circleTime     周期时间值
     * @return 下次执行时间
     */
    private LocalDateTime getNextExecuteTime(LocalDateTime lastTime, String circleTimeUnit, Integer circleTime, LocalDateTime now) {
        if (lastTime.isAfter(now) || lastTime.equals(now)) {
            return lastTime;
        }
        while (lastTime.isBefore(now)) {
            lastTime = getNextCircleTime(lastTime, circleTimeUnit, circleTime);
        }
        return lastTime;
    }

    /**
     * 后推一个周期
     */
    private LocalDateTime getNextCircleTime(LocalDateTime lastTime, String circleTimeUnit, Integer circleTime) {
        LocalDateTime results = null;
        if (TimeUnitEnum.MINUTE.getCode().equals(circleTimeUnit)) {
            results = lastTime.plusMinutes(circleTime);
        }
        if (TimeUnitEnum.HOUR.getCode().equals(circleTimeUnit)) {
            results = lastTime.plusHours(circleTime);
        }
        if (TimeUnitEnum.DAY.getCode().equals(circleTimeUnit)) {
            results = lastTime.plusDays(circleTime);
        }
        return results;
    }

    /**
     * 获取通知时间
     *
     * @param nextExecuteTime 下次任务执行时间
     * @param noticeTimeUnit  提前通知时间单位
     * @param noticeTime      提前通知时间值
     * @return 通知时间
     */
    private LocalDateTime getNoticeTime(LocalDateTime nextExecuteTime, String noticeTimeUnit, Integer noticeTime) {
        LocalDateTime results = null;
        if (TimeUnitEnum.MINUTE.getCode().equals(noticeTimeUnit)) {
            results = nextExecuteTime.minusMinutes(noticeTime);
        }
        if (TimeUnitEnum.HOUR.getCode().equals(noticeTimeUnit)) {
            results = nextExecuteTime.minusHours(noticeTime);
        }
        if (TimeUnitEnum.DAY.getCode().equals(noticeTimeUnit)) {
            results = nextExecuteTime.minusDays(noticeTime);
        }
        return results;

    }

    /**
     * 一次性任务处理
     *
     * @param onceList           一次性计划列表
     * @param planDetailsMap     计划详情Map <planId,detailsList>
     * @param now                当前时间
     * @param updatePlanList     需要更新的计划列表
     * @param addTaskList        需要新增的任务列表
     * @param addTaskDetailsList 需要新增的任务明细列表
     */
    private void oncePlanHandle(List<TpmMaintenancePlanEntity> onceList,
                                Map<Long, List<TpmMaintenancePlanDetailsEntity>> planDetailsMap,
                                LocalDateTime now,
                                List<TpmMaintenancePlanEntity> updatePlanList,
                                List<TpmMaintenanceTaskEntity> addTaskList,
                                List<TpmMaintenanceTaskDetailsEntity> addTaskDetailsList) {
        for (TpmMaintenancePlanEntity plan : onceList) {
            //判断是否存在上次执行时间，存在说明任务执行过，不会重复执行
            if (ObjectUtil.isEmpty(plan.getLastTime())) {
                //获取执行时间，如果当前时间 = 执行时间 或者在执行时间之后，创建任务，更改计划上次执行时间
                LocalDateTime startTime = plan.getStartTime();
                //获取通知时间，如果当前时间 = 通知时间 或者在通知时间之后，是否发送通知消息
                LocalDateTime noticeTime = getNoticeTime(plan.getStartTime(), plan.getNoticeTimeUnit(), plan.getNoticeTime());
                if (now.equals(noticeTime) || now.isAfter(noticeTime)) {
                    if (YesOrNoStateEnum.NO.getCode().equals(plan.getNoticeFlag())) {
                        sendMsg(plan, startTime);
                        plan.setNoticeFlag(YesOrNoStateEnum.YES.getCode());
                        updatePlanList.add(plan);
                    }
                }
                if (now.equals(startTime) || now.isAfter(startTime)) {
                    //创建任务
                    String taskCode = remoteSeqConfigService.create("device_maintenance_task").getData();
                    TpmMaintenanceTaskEntity task = new TpmMaintenanceTaskEntity();
                    BeanUtils.copyProperties(plan, task, "id");
                    task.setPlanId(plan.getId());
                    task.setPlanName(plan.getName());
                    task.setState(MaintenanceTaskStateEnum.WAIT.getCode());
                    task.setTaskCode(taskCode);
                    addTaskList.add(task);
                    //创建任务明细
                    List<TpmMaintenancePlanDetailsEntity> detailsList = planDetailsMap.get(plan.getId());
                    addTaskDetailsList.addAll(detailsList.stream().map(item -> {
                        TpmMaintenanceTaskDetailsEntity taskDetails = new TpmMaintenanceTaskDetailsEntity();
                        BeanUtils.copyProperties(item, taskDetails, "id");
                        taskDetails.setTaskCode(taskCode);
                        return taskDetails;
                    }).toList());
                    //更改上次执行时间
                    plan.setLastTime(now);
                    updatePlanList.add(plan);
                }
            }

        }
    }


    /**
     * 周期性任务处理
     *
     * @param cycleList          周期性计划列表
     * @param planDetailsMap     计划详情Map <planId,detailsList>
     * @param now                当前时间
     * @param updatePlanList     需要更新的计划列表
     * @param addTaskList        需要新增的任务列表
     * @param addTaskDetailsList 需要新增的任务明细列表
     */
    private void cyclePlanHandle(List<TpmMaintenancePlanEntity> cycleList,
                                 Map<Long, List<TpmMaintenancePlanDetailsEntity>> planDetailsMap,
                                 LocalDateTime now,
                                 List<TpmMaintenancePlanEntity> updatePlanList,
                                 List<TpmMaintenanceTaskEntity> addTaskList,
                                 List<TpmMaintenanceTaskDetailsEntity> addTaskDetailsList) {
        for (TpmMaintenancePlanEntity plan : cycleList) {
            //获取下一次执行日期
            LocalDateTime lastTime = ObjectUtil.isNotEmpty(plan.getLastTime()) ? plan.getLastTime() : plan.getStartTime();
            lastTime = lastTime.withSecond(0);//精确到分钟

            LocalDateTime nextExecuteTime = getNextExecuteTime(lastTime, plan.getCircleTimeUnit(), plan.getCircleTime(), now);
            LocalDateTime noticeTime = getNoticeTime(nextExecuteTime, plan.getNoticeTimeUnit(), plan.getNoticeTime());

            if (now.equals(noticeTime) || now.isAfter(noticeTime)) {
                if (YesOrNoStateEnum.NO.getCode().equals(plan.getNoticeFlag())) {
                    sendMsg(plan, nextExecuteTime);
                    plan.setNoticeFlag(YesOrNoStateEnum.YES.getCode());
                    updatePlanList.add(plan);
                }
            }

            if (now.equals(nextExecuteTime) || now.isAfter(nextExecuteTime)) {
                //创建任务
                String taskCode = remoteSeqConfigService.create("device_maintenance_task").getData();
                TpmMaintenanceTaskEntity task = new TpmMaintenanceTaskEntity();
                BeanUtils.copyProperties(plan, task, "id");
                task.setPlanId(plan.getId());
                task.setPlanName(plan.getName());
                task.setState(MaintenanceTaskStateEnum.WAIT.getCode());
                task.setTaskCode(taskCode);
                addTaskList.add(task);
                //创建任务明细
                List<TpmMaintenancePlanDetailsEntity> detailsList = planDetailsMap.get(plan.getId());
                addTaskDetailsList.addAll(detailsList.stream().map(item -> {
                    TpmMaintenanceTaskDetailsEntity taskDetails = new TpmMaintenanceTaskDetailsEntity();
                    BeanUtils.copyProperties(item, taskDetails, "id");
                    taskDetails.setTaskCode(taskCode);
                    return taskDetails;
                }).toList());
                //更改上次执行时间
                plan.setLastTime(now);
                plan.setNoticeFlag(YesOrNoStateEnum.NO.getCode());
                updatePlanList.add(plan);
            }
        }
    }

    private void sendMsg(TpmMaintenancePlanEntity plan, LocalDateTime nextExecuteTime) {
        //获取发送对象
        UserInfo data = remoteUserService.info(plan.getHandlePersonCode()).getData();
        if (ObjectUtil.isEmpty(data)) {
            log.info("获取用户信息失败=========================>");
        }

        if (MaintenancePlanNoticeTypeEnum.SYS.getCode().equals(plan.getNoticeType())) {
            sendSysMsg(data, nextExecuteTime);
        }
        if (MaintenancePlanNoticeTypeEnum.DING.getCode().equals(plan.getNoticeType())) {
            sendDingMsg(data, nextExecuteTime);
        }
        if (MaintenancePlanNoticeTypeEnum.ALL.getCode().equals(plan.getNoticeType())) {
            sendSysMsg(data, nextExecuteTime);
            sendDingMsg(data, nextExecuteTime);
        }
    }

    private void sendSysMsg(UserInfo data, LocalDateTime nextExecuteTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        String message = String.format(MESSAGE, nextExecuteTime.format(formatter));
        //构建实体
        SysMessageVO sysMessageVO = new SysMessageVO();
        sysMessageVO.setAllFlag("0");
        sysMessageVO.setCategory("1");
        sysMessageVO.setContent(message);
        sysMessageVO.setSort(0);
        sysMessageVO.setSendFlag(YesOrNoStateEnum.YES.getCode());
        sysMessageVO.setTitle("设备保养");
        List<OrgTreeVO> userList = new ArrayList<>();
        userList.add(new OrgTreeVO(ObjectUtil.isEmpty(data) ? null : data.getSysUser().getUserId(), null, null, null, null));
        sysMessageVO.setUserList(userList);
        sysMessageVO.setCreateBy("admin");
        sysMessageVO.setCreateTime(LocalDateTime.now());
        R r = remoteMessageService.senMsg(sysMessageVO);
        if (r.getCode() != 0) {
            log.info("系统消息推送失败=========================>");
        }

    }

    private void sendDingMsg(UserInfo data, LocalDateTime nextExecuteTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        //构建消息体
        String message = String.format(MESSAGE, nextExecuteTime.format(formatter));
        //构建参数推送消息
        MessageDTO messageDTO = new MessageDTO();
        messageDTO.setCode(TPMDELAY);
        messageDTO.setMessageType(MessageTypeEnum.TEXT.getType());
        messageDTO.setUserList(Collections.singletonList(ObjectUtil.isEmpty(data) ? null : data.getSysUser().getUserId()));
        messageDTO.setUserIds(ObjectUtil.isEmpty(data) ? "" : data.getSysUser().getWxDingUserid());
        messageDTO.setMessage(message);
        R<Boolean> booleanR = remoteMessagePushService.pushPersonMsg(messageDTO);
        if (booleanR.getCode() != 0) {
            log.info("消息推送失败=========================>");
        }
    }


}
