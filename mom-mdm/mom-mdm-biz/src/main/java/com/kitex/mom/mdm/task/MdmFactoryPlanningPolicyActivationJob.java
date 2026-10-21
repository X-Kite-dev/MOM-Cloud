package com.kitex.mom.mdm.task;

import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 计划协同策略预约激活任务；建议每分钟调度。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MdmFactoryPlanningPolicyActivationJob {
    private final MdmFactoryPlanningPolicyActivationWorker worker;

    @XxlJob("mdm_factory_planning_policy_activate")
    public void activateDue() {
        int activated = 0;
        int failed = 0;
        for (var candidate : worker.discoverDue(100)) {
            try {
                if (worker.activateOne(candidate.getId(), candidate.getTenantId(), candidate.getFactoryCode())) {
                    activated++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.error("计划协同策略预约激活异常，policyId={}，tenantId={}，factoryCode={}",
                        candidate.getId(), candidate.getTenantId(), candidate.getFactoryCode(), ex);
            }
        }
        log.info("计划协同策略预约激活完成，成功={}，失败={}", activated, failed);
    }
}
