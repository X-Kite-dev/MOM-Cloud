package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.agent.dto.WmsBatchStockTraceDTO;
import com.kitex.mom.wms.agent.dto.WmsInventoryHealthDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * WMS 面向智能体的受控只读查询。
 */
@FeignClient(contextId = "remoteWmsAgentQueryService", value = Constants.Name)
public interface RemoteWmsAgentQueryService {

    @InternalCall(userTokenRequired = true)
    @PostMapping("/wmsAgentQuery/internal/inventory-health")
    R<WmsInventoryHealthDTO.Evidence> inventoryHealth(
            @RequestBody WmsInventoryHealthDTO.Query query);

    @InternalCall(userTokenRequired = true)
    @PostMapping("/wmsAgentQuery/internal/batch-stock-trace")
    R<WmsBatchStockTraceDTO.Evidence> batchStockTrace(
            @RequestBody WmsBatchStockTraceDTO.Query query);

}
