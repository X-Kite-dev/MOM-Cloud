package com.kitex.mom.plan.service;

import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;

import java.time.LocalDate;

/**
 * 排产自动触发与补偿服务
 */
public interface PlnSchedulingJobService {

    /**
     * MRP完成后触发排产任务
     *
     * @param cycleType    计划周期 DAY/WEEK/MONTH
     * @param scheduleDate 计划日期
     * @return 是否触发成功
     */
    Boolean onMrpCompleted(String cycleType, LocalDate scheduleDate);

    /**
     * 补偿重试失败任务
     *
     * @return 是否执行成功
     */
    Boolean retryFailedTasks();

    /**
     * 单条失败任务补偿重试
     *
     * @param task 失败任务
     * @return 是否成功
     */
    Boolean retryFailedTask(PlnSchedulingTaskEntity task);
}
