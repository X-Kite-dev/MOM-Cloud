package com.kitex.mom.plan.task;

import com.kitex.mom.plan.service.impl.OmsFulfillmentRiskAutomationService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OmsFulfillmentRiskAutomationJob {
    private final OmsFulfillmentRiskAutomationService automationService;

    /**
     * 建议每5分钟运行；业务扫描间隔由配置控制，跨租户候选逐行独立事务。
     */
    @XxlJob("oms_fulfillment_risk_scan")
    public void scan() {
        log.info("订单履约风险自动扫描完成：{}", automationService.scanDue());
    }

    /**
     * 建议每分钟运行；结果不明消息会转人工核对，不会自动重复发送。
     */
    @XxlJob("oms_fulfillment_risk_notice_dispatch")
    public void dispatchNotices() {
        log.info("订单履约风险催办投递完成：{}", automationService.dispatchDue());
    }
}
