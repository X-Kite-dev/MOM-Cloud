package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesPlanCompletionReopenEvidence;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * PLAN只读核验MES报工冲销导致的完工重新打开事实。
 */
@FeignClient(contextId = "remoteMesPlanCompletionReopenService", value = Constants.Name)
public interface RemoteMesPlanCompletionReopenService {

    @InternalCall
    @GetMapping("/mesTask/plan-completion-reopen/internal/evidence")
    R<MesPlanCompletionReopenEvidence> evidence(@RequestParam("outboxId") Long outboxId,
                                                @RequestParam("factoryCode") String factoryCode);
}
