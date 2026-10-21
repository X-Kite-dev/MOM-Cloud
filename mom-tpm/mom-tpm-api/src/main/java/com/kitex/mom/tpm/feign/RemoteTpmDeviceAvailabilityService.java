package com.kitex.mom.tpm.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.Constants;
import com.kitex.mom.tpm.dto.TpmDeviceAvailabilityQueryDTO;
import com.kitex.mom.tpm.dto.TpmDeviceUnavailableWindowDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(contextId = "remoteTpmDeviceAvailabilityService", value = Constants.Name)
public interface RemoteTpmDeviceAvailabilityService {

    @PostMapping("/tpmDeviceAvailability/windows")
    R<List<TpmDeviceUnavailableWindowDTO>> unavailableWindows(@RequestBody TpmDeviceAvailabilityQueryDTO query);
}
