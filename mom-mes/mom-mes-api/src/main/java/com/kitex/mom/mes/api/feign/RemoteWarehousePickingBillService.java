package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesPickingWmsOutBillBackDTO;
import com.kitex.mom.mes.dto.MesWmsIssueEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 生产领料单
 */
@FeignClient(contextId = "remoteWarehousePickingBillService", value = Constants.Name)
public interface RemoteWarehousePickingBillService {

    @InternalCall
    @PostMapping("/mesWmsIssue/internal/receive")
    R<MesWmsIssueEvent.Receipt> receiveIssueEvent(@RequestBody MesWmsIssueEvent event);

    @InternalCall
    @GetMapping("/mesWmsIssue/internal/receipt")
    R<MesWmsIssueEvent.Receipt> issueEventReceipt(@RequestParam("eventId") String eventId);

    /**
     * 领料出库单完成后返回结果
     */
    @PostMapping("/mesWarehousePickingBill/wmsOutBillBack")
    @InternalCall
    R<Boolean> wmsOutBillBack(@RequestBody MesPickingWmsOutBillBackDTO dto);
}
