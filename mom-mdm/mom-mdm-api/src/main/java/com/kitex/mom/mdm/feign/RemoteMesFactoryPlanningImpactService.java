package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * MES 工厂计划权威切换影响读取客户端。
 */
@FeignClient(contextId = "remoteMesFactoryPlanningImpactService", value = "kitex-mom-mes")
public interface RemoteMesFactoryPlanningImpactService {
    @InternalCall(userTokenRequired = false)
    @GetMapping("/factoryPlanningImpact/internal/current")
    R<MdmFactoryPlanningImpactDTO.ModuleImpact> current(@RequestParam("factoryCode") String factoryCode);
}
