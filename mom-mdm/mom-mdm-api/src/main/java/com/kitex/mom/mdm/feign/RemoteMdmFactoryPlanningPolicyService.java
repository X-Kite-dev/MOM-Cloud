package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * MDM 工厂计划权威内部只读客户端。
 */
@FeignClient(contextId = "remoteMdmFactoryPlanningPolicyService", value = Constants.Name)
public interface RemoteMdmFactoryPlanningPolicyService {
    @NoToken
    @GetMapping("/mdmFactoryPlanningPolicy/internal/active")
    R<MdmFactoryPlanningPolicyDTO> active(@RequestParam("factoryCode") String factoryCode);
}
