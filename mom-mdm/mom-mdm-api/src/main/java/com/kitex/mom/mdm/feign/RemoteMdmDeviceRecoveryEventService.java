package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmDeviceRecoveryEventEvidence;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(contextId = "remoteMdmDeviceRecoveryEventService", value = Constants.Name)
public interface RemoteMdmDeviceRecoveryEventService {
    @NoToken
    @GetMapping("/mdmDevice/controlled-recovery/inner-event/{receiptId}")
    R<MdmDeviceRecoveryEventEvidence> evidence(@PathVariable("receiptId") Long receiptId, @RequestParam("factoryCode") String factoryCode);

    @NoToken
    @GetMapping("/mdmDevice/controlled-recovery/inner-latest")
    R<MdmDeviceRecoveryEventEvidence> latest(@RequestParam("deviceCode") String deviceCode,
                                             @RequestParam("factoryCode") String factoryCode);
}
