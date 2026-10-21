package com.kitex.mom.pms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.pms.Constants;
import com.kitex.mom.pms.agent.dto.PmsProcurementRiskDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * PMS 面向智能体的受控只读查询。
 */
@FeignClient(contextId = "remotePmsAgentQueryService", value = Constants.Name)
public interface RemotePmsAgentQueryService {

    @InternalCall(userTokenRequired = true)
    @PostMapping("/pmsAgentQuery/internal/procurement-risk")
    R<PmsProcurementRiskDTO.Evidence> procurementRisk(
            @RequestBody PmsProcurementRiskDTO.Query query);
}
