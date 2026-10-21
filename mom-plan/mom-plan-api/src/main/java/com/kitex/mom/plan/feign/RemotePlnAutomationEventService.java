package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.Constants;
import com.kitex.mom.plan.dto.PlanAutomationEventDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** TPM、MDM、WMS、QMS等权威源投递制造事件的内部契约。 */
@FeignClient(contextId = "remotePlnAutomationEventService", value = Constants.Name)
public interface RemotePlnAutomationEventService {
    @InternalCall
    @PostMapping("/plnAutomationEvent/receive")
    R<PlanAutomationEventDTO.ReceiveReceipt> receive(@RequestBody PlanAutomationEventDTO.ReceiveCommand command);
}
