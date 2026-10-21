package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesRepairResumeEvidence;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * PLAN只读核验MES维修停工后的真实复工区间和同事务来源事件。
 */
@FeignClient(contextId = "remoteMesRepairResumeEventService", value = Constants.Name)
public interface RemoteMesRepairResumeEventService {
    @InternalCall
    @GetMapping("/mesTask/repair-resume-event/internal/evidence")
    R<MesRepairResumeEvidence> evidence(@RequestParam("outboxId") Long outboxId,
                                        @RequestParam("factoryCode") String factoryCode);
}
