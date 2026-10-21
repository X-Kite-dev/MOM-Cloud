package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesFinishedGoodsReceiptReverseEvent;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * WMS成品实物红冲可靠回写MES的内部契约。
 */
@FeignClient(contextId = "remoteMesFinishedGoodsReceiptReverseService", value = Constants.Name)
public interface RemoteMesFinishedGoodsReceiptReverseService {
    @PostMapping("/finishedGoodsReceiptReverse/internal/receive")
    R<MesFinishedGoodsReceiptReverseEvent.Receipt> receive(
            @RequestBody MesFinishedGoodsReceiptReverseEvent event);

    @GetMapping("/finishedGoodsReceiptReverse/internal/receipt")
    R<MesFinishedGoodsReceiptReverseEvent.Receipt> receipt(@RequestParam("eventId") String eventId);
}
