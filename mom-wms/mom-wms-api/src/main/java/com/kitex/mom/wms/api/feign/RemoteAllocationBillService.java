package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.dto.WmsCrossWarehouseTransferDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(contextId = "remoteAllocationBillService", value = Constants.Name)
public interface RemoteAllocationBillService {

    @PostMapping("/wmsAllocationBill/agent/transfer/recommendations")
    R<List<WmsCrossWarehouseTransferDTO.Recommendation>> transferRecommendations(
            @RequestBody WmsCrossWarehouseTransferDTO.Query query);

    @PostMapping("/wmsAllocationBill/agent/transfer/execute")
    R<WmsCrossWarehouseTransferDTO.Receipt> executeTransfer(
            @RequestBody WmsCrossWarehouseTransferDTO.AgentCommand command);

    /**
     * 只查原永久号与实际出库流水，不重放业务、不扣库存。
     */
    @PostMapping("/wmsAgentTransfer/receipt")
    R<WmsCrossWarehouseTransferDTO.Receipt> transferReceipt(
            @RequestBody WmsCrossWarehouseTransferDTO.AgentCommand command);

    /**
     * 明确查证旧调拨永久号是否占用；失败不等于ABSENT。
     */
    @PostMapping("/wmsAgentTransfer/presence")
    R<Map<String, String>> transferPresence(@RequestBody Map<String, String> query);
}
