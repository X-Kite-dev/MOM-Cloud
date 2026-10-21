package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmWorkforceAvailabilityQueryDTO;
import com.kitex.mom.mdm.dto.MdmWorkforceCandidateDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(contextId = "remoteMdmWorkforceService", value = Constants.Name)
public interface RemoteMdmWorkforceService {
    @PostMapping("/mdmWorkforce/availability")
    R<List<MdmWorkforceCandidateDTO>> availability(@RequestBody MdmWorkforceAvailabilityQueryDTO query);
}
