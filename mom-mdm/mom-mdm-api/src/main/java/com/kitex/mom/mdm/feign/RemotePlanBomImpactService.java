package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.dto.MdmBomImpact;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(contextId = "remotePlanBomImpactService", value = "kitex-mom-plan")
public interface RemotePlanBomImpactService {
    @InternalCall(userTokenRequired = true)
    @PostMapping("/planBomImpact/internal/page")
    R<MdmBomImpact.Batch> page(@RequestBody MdmBomImpact.PageQuery query);

    @InternalCall(userTokenRequired = true)
    @PostMapping("/planBomImpact/internal/query")
    R<List<MdmBomImpact.Row>> query(@RequestBody MdmBomImpact.Query query);
}
