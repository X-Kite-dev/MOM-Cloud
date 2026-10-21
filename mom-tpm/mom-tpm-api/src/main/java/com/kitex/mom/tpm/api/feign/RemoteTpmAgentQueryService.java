package com.kitex.mom.tpm.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.tpm.Constants;
import com.kitex.mom.tpm.agent.dto.TpmEquipmentOverviewDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * TPM 面向智能体的受控只读查询。
 */
@FeignClient(contextId = "remoteTpmAgentQueryService", value = Constants.Name)
public interface RemoteTpmAgentQueryService {

    @InternalCall(userTokenRequired = true)
    @PostMapping("/tpmAgentQuery/internal/equipment-overview")
    R<TpmEquipmentOverviewDTO.Evidence> equipmentOverview(
            @RequestBody TpmEquipmentOverviewDTO.Query query);
}
