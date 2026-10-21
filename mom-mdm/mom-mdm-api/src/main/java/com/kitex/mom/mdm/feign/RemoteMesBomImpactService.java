package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.dto.MdmBomImpact;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(contextId = "remoteMesBomImpactService", value = "kitex-mom-mes")
public interface RemoteMesBomImpactService {
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesBomImpact/internal/page")
    R<MdmBomImpact.Batch> page(@RequestBody MdmBomImpact.PageQuery query);

    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesBomImpact/internal/query")
    R<List<MdmBomImpact.Row>> query(@RequestBody MdmBomImpact.Query query);
}
