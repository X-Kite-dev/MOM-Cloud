package com.kitex.mom.pms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.pms.Constants;
import com.kitex.mom.pms.dto.PmsPurchaseInboundPosting;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 以现有实名操作上下文核采购来源、投递已提交的入库事实和查询 PMS 永久回执。
 */
@FeignClient(contextId = "remotePmsPurchaseInboundPostingService", value = Constants.Name)
public interface RemotePmsPurchaseInboundPostingService {

	/**
	 * 核对原收料及 WMS 原通知目标仓的 V1 准入，明确返回 false 时保留旧模式。
	 *
	 * @param purchaseReceiptId PMS 原收料单主键
	 * @param warehouseCode WMS 原采购入库通知的目标仓库编码
	 * @return 是否采用 V1，实际仓库冲突返回业务失败
	 */
	@InternalCall(userTokenRequired = true)
	@GetMapping("/wmsPurchaseReceipt/internal/wms-inbound-posting/eligibility/{purchaseReceiptId}")
	R<Boolean> eligible(@PathVariable("purchaseReceiptId") Long purchaseReceiptId,
	                    @RequestParam("warehouseCode") String warehouseCode);

	/** 按原入库单投递，PMS 已受理时按同一原文回读永久回执。 */
	@InternalCall(userTokenRequired = true)
	@PostMapping("/wmsPurchaseReceipt/internal/wms-inbound-posting")
	R<PmsPurchaseInboundPosting.Receipt> accept(@RequestBody PmsPurchaseInboundPosting command);

	/** 成功返回空表示在当前实际组织未找到原号，查询失败不视为未受理。 */
	@InternalCall(userTokenRequired = true)
	@GetMapping("/wmsPurchaseReceipt/internal/wms-inbound-posting/{wmsBillId}")
	R<PmsPurchaseInboundPosting.Receipt> receipt(@PathVariable("wmsBillId") Long wmsBillId);
}
