package com.kitex.mom.plan.task;

import com.kitex.mom.plan.service.impl.OmsDeliveryWithdrawalService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OmsDeliveryWithdrawalJob {
    private final OmsDeliveryWithdrawalService service;

    /**
     * 建议每分钟调度；需要在XXL-JOB注册本处理器，部署前不会自行启动。
     */
    @XxlJob("oms_delivery_withdrawal_retry")
    public void retryDue() {
        int completed = service.processDue(100);
        log.info("OMS交付行撤回续跑完成，本轮成功数={}", completed);
    }
}
