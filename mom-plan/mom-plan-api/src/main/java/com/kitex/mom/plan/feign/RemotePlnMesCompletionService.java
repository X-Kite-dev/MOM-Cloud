package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.Constants;
import com.kitex.mom.plan.dto.PlanMesCompletionDTO;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(contextId = "remotePlnMesCompletionService", value = Constants.Name)
public interface RemotePlnMesCompletionService {
    @InternalCall
    @PostMapping("/mes-completion/internal/reconcile")
    R<PlanMesCompletionDTO.Receipt> reconcile(@RequestBody PlanMesCompletionDTO.Command command);
}
