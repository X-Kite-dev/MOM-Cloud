package com.kitex.mom.plan.task;

import com.kitex.mom.plan.service.PlnSchedulingJobService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlnSchedulingRetryJob {

    private final PlnSchedulingJobService plnSchedulingJobService;

    /**
     * 排产失败任务补偿重试
     */
    @XxlJob("pln_scheduling_retry")
    public void retryFailedSchedulingTasks() {
        log.info("排产补偿任务开始执行");
        Boolean result = plnSchedulingJobService.retryFailedTasks();
        log.info("排产补偿任务执行完成, result={}", result);
    }
}
