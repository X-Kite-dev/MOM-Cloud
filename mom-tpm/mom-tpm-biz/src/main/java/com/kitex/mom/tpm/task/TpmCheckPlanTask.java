package com.kitex.mom.tpm.task;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.tpm.entity.*;
import com.kitex.mom.tpm.enums.CheckTaskSourceEnum;
import com.kitex.mom.tpm.enums.CheckTaskStateEnum;
import com.kitex.mom.tpm.enums.PlanExecuteStateEnum;
import com.kitex.mom.tpm.enums.TimeUnitEnum;
import com.kitex.mom.tpm.service.*;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;


@Slf4j
@Component
@RequiredArgsConstructor
public class TpmCheckPlanTask {

    //设备-检项关系
    private final TpmDeviceItemService tpmDeviceItemService;
    //检项
    private final TpmCheckItemService tpmCheckItemService;
    //计划
    private final TpmCheckPlanService tpmCheckPlanService;
    //任务
    private final TpmCheckTaskService tpmCheckTaskService;
    private final TpmCheckTaskDetailsService tpmCheckTaskDetailsService;
    private final RemoteSeqConfigService remoteSeqConfigService;


    /**
     * 点检计划 - 生成点检任务
     */
    @XxlJob("device_check")
//	@Scheduled(cron = "0 * * * * ? ")
    public void deviceCheck() {
        log.info("设备点检定时任务 start==>");
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        //新增任务列表以及任务详情
        List<TpmCheckPlanEntity> updatePlanList = new ArrayList<>();
        List<TpmCheckTaskEntity> addTaskList = new ArrayList<>();
        List<TpmCheckTaskDetailsEntity> addTaskDetailsList = new ArrayList<>();
        //数据准备 ===>查询现有的计划和计划明细
        //查询所有执行中的有效期范围内的点/巡检计划
        List<TpmCheckPlanEntity> planList = tpmCheckPlanService.list(
                new LambdaQueryWrapper<TpmCheckPlanEntity>().eq(TpmCheckPlanEntity::getExecuteState, PlanExecuteStateEnum.ING.getCode())
                        .le(TpmCheckPlanEntity::getPlanStart, now)
                        .ge(TpmCheckPlanEntity::getPlanEnd, now));

        //查询所有对应的点检项目 -- todo 后期deivceItem表加入保养项目，这个地方根据类型只查询点/巡检
        List<TpmCheckItemEntity> itemList = tpmCheckItemService.list();
        Map<Long, TpmCheckItemEntity> itemMap = itemList.stream().collect(Collectors.toMap(TpmCheckItemEntity::getId, Function.identity(), (k1, k2) -> k1));
        List<TpmDeviceItemEntity> deviceItemList = tpmDeviceItemService.list();
        for (TpmDeviceItemEntity entity : deviceItemList) {
            TpmCheckItemEntity itemEntity = itemMap.get(entity.getItemId());
            if (ObjectUtil.isEmpty(itemEntity)) {
                log.info("数据查询失败，数据错误");
                deviceItemList.remove(entity);
            } else {
                BeanUtils.copyProperties(itemEntity, entity, "id");
            }
        }
        Map<String, List<TpmDeviceItemEntity>> deviceItemMap = deviceItemList.stream().collect(Collectors.groupingBy(TpmDeviceItemEntity::getDeviceCode, Collectors.toList()));


        //计划处理
        cyclePlanHandle(planList, deviceItemMap, now, updatePlanList, addTaskList, addTaskDetailsList);

        tpmCheckPlanService.updateBatchById(updatePlanList);
        tpmCheckTaskService.saveBatch(addTaskList);
        tpmCheckTaskDetailsService.saveBatch(addTaskDetailsList);
        log.info("设备保养定时任务 ==>end");
    }


    /**
     * 任务处理
     *
     * @param planList           时间范围内运行的 ，点/巡检计划
     * @param deviceItemMap      设备绑定的点/巡检项Map <deviceCode,detailsList>
     * @param now                当前时间
     * @param addTaskList        需要新增的任务列表
     * @param addTaskDetailsList 需要新增的任务明细列表
     */
    private void cyclePlanHandle(List<TpmCheckPlanEntity> planList,
                                 Map<String, List<TpmDeviceItemEntity>> deviceItemMap,
                                 LocalDateTime now,
                                 List<TpmCheckPlanEntity> updatePlanList,
                                 List<TpmCheckTaskEntity> addTaskList,
                                 List<TpmCheckTaskDetailsEntity> addTaskDetailsList) {
        for (TpmCheckPlanEntity plan : planList) {
            //获取下一次执行日期
            LocalDateTime lastTime = ObjectUtil.isNotEmpty(plan.getLastTime()) ? plan.getLastTime() : plan.getPlanStart();
            lastTime = lastTime.withSecond(0);//精确到分钟
            //下次执行时间
            LocalDateTime nextExecuteTime = getNextExecuteTime(lastTime, plan.getCircleTimeUnit(), plan.getCircleTime(), now);

            if (now.equals(nextExecuteTime) || now.isAfter(nextExecuteTime)) {

                List<TpmDeviceItemEntity> deviceItemEntities = deviceItemMap.get(plan.getDeviceCode());
                if (CollUtil.isEmpty(deviceItemEntities)) {
                    log.info("生成任务失败！失败原因：未维护检验项目！设备编码:【" + plan.getDeviceCode() + "】");
                    continue;
                }
                List<TpmDeviceItemEntity> relaList = deviceItemEntities.stream().filter(item -> plan.getType().equals(item.getType())).toList();
                if (CollUtil.isEmpty(relaList)) {
                    log.info("生成任务失败！失败原因：未维护对应的检验项目！设备编码:【" + plan.getDeviceCode() + "】");
                    continue;
                }
                //创建任务
                String taskCode = remoteSeqConfigService.create("device_check_task").getData();
                TpmCheckTaskEntity task = new TpmCheckTaskEntity();
                BeanUtils.copyProperties(plan, task, "id");
                task.setTaskCode(taskCode);
                task.setPlanId(plan.getId());
                task.setPlanName(plan.getName());
                task.setState(CheckTaskStateEnum.ASSIGNED.getCode());
                task.setSource(CheckTaskSourceEnum.PLAN.getCode());
                addTaskList.add(task);
                //创建任务明细
                relaList.forEach(item -> {
                    TpmCheckTaskDetailsEntity checkTaskDetail = new TpmCheckTaskDetailsEntity();
                    BeanUtils.copyProperties(item, checkTaskDetail, "id");
                    checkTaskDetail.setTaskCode(taskCode);
                    checkTaskDetail.setItemCode(item.getCode());
                    checkTaskDetail.setItemName(item.getName());
                    addTaskDetailsList.add(checkTaskDetail);
                });

                //更改上次执行时间
                plan.setLastTime(now);
                updatePlanList.add(plan);
            }
        }
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

}
