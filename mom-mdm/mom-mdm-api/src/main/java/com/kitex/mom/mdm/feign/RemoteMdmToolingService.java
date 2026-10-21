package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmToolingAvailabilityQueryDTO;
import com.kitex.mom.mdm.dto.MdmToolingCandidateDTO;
import com.kitex.mom.mdm.dto.MdmToolingUsageRequestDTO;
import com.kitex.mom.mdm.dto.MdmToolingUsageResultDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(contextId = "remoteMdmToolingService", value = Constants.Name)
public interface RemoteMdmToolingService {
    @PostMapping("/mdmTooling/availability")
    R<List<MdmToolingCandidateDTO>> availability(@RequestBody MdmToolingAvailabilityQueryDTO query);

    @InternalCall
    @PostMapping("/mdmTooling/life/consume")
    R<List<MdmToolingUsageResultDTO>> consumeLife(@RequestBody MdmToolingUsageRequestDTO request);
}
