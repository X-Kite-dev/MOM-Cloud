package com.kitex.mom.wms.client;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.dto.MesMaterialReturnReceiptEvent;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * WMS拥有的后台实收投递适配；沿MES既有内部路径/DTO，保留接收端鉴权。
 */
@FeignClient(contextId = "wmsMesMaterialReturnReceiptClient", value = "kitex-mom-mes")
public interface WmsMesMaterialReturnReceiptClient {
	@InternalCall
	@PostMapping("/mesMaterialReturnReceipt/internal/receive")
	R<MesMaterialReturnReceiptEvent.Receipt> receive(@RequestBody MesMaterialReturnReceiptEvent event);

	@InternalCall
	@GetMapping("/mesMaterialReturnReceipt/internal/receipt")
	R<MesMaterialReturnReceiptEvent.Receipt> receipt(@RequestParam("eventId") String eventId);
}
