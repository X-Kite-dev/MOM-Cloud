package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.Constants;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** MES 接单时二次核验 PLAN 正式计划合同的内部客户端。 */
@FeignClient(contextId = "remotePlanFormalContractService", value = Constants.Name)
public interface RemotePlanFormalContractService {

    @InternalCall
    @GetMapping("/erpPlanIntegration/internal/verified")
    R<PlnFormalPlanContractDTO> verified(@RequestParam("contractNo") String contractNo,
                                         @RequestParam("contractVersion") Integer contractVersion);
}
