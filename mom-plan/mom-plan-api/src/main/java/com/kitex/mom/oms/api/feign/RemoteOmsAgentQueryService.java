package com.kitex.mom.oms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.Constants;
import com.kitex.mom.oms.agent.dto.OmsDeliveryPromiseDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** OMS/PLAN 面向智能体的受控只读查询。 */
@FeignClient(contextId = "remoteOmsAgentQueryService", value = Constants.Name)
public interface RemoteOmsAgentQueryService {

    @InternalCall(userTokenRequired = true)
    @PostMapping("/omsAgentQuery/internal/delivery-promise")
    R<OmsDeliveryPromiseDTO.Evidence> deliveryPromise(@RequestBody OmsDeliveryPromiseDTO.Query query);

}
