package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesTaskCommitmentSourceDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * PLAN反查真实MES工单来源，保留当前实名与实际组织，不信任客户端计划ID。
 */
@FeignClient(contextId = "remoteMesTaskCommitmentSourceService", value = Constants.Name)
public interface RemoteMesTaskCommitmentSourceService {
    @InternalCall(userTokenRequired = true)
    @GetMapping("/mesTask/remaining-commitment/internal/sources")
    R<MesTaskCommitmentSourceDTO.Evidence> sources(@RequestParam("taskCode") String taskCode);
}
