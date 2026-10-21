package com.kitex.mom.plan.task;

import com.kitex.mom.plan.service.impl.OmsDeliveryIssueService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OmsDeliveryIssueJob {
    private final OmsDeliveryIssueService service;

    /**
     * 建议每分钟调度；部署时须在XXL-JOB注册。
     */
    @XxlJob("oms_delivery_issue_retry")
    public void retryDue() {
        int completed = service.processDue(100);
        log.info("OMS交付行下发续跑完成，本轮成功数={}", completed);
    }
}
