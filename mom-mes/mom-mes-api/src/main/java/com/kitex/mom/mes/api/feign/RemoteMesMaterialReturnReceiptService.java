package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesMaterialReturnReceiptEvent;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * WMS生产退料实收回写MES的内部接口。
 */
@FeignClient(contextId = "remoteMesMaterialReturnReceiptService", value = Constants.Name)
public interface RemoteMesMaterialReturnReceiptService {

    @PostMapping("/mesMaterialReturnReceipt/internal/receive")
    R<MesMaterialReturnReceiptEvent.Receipt> receive(@RequestBody MesMaterialReturnReceiptEvent event);

    @GetMapping("/mesMaterialReturnReceipt/internal/receipt")
    R<MesMaterialReturnReceiptEvent.Receipt> receipt(@RequestParam("eventId") String eventId);
}
