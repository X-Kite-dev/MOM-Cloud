package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesPlanCompletionDTO;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(contextId = "remoteMesPlanCompletionService", value = Constants.Name)
public interface RemoteMesPlanCompletionService {
    @InternalCall
    @GetMapping("/mesTask/plan-completion/internal/evidence")
    R<MesPlanCompletionDTO.Evidence> evidence(
            @RequestParam("outboxId") Long outboxId,
            @RequestParam("factoryCode") String factoryCode);

    @InternalCall
    @GetMapping("/mesTask/plan-completion/internal/progress")
    R<MesPlanCompletionDTO.Progress> progress(
            @RequestParam("outboxId") Long outboxId,
            @RequestParam("issueId") Long issueId,
            @RequestParam("issueNo") String issueNo,
            @RequestParam("factoryCode") String factoryCode);
}
