package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.wms.Constants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** PMS 按实际组织读取 WMS 已提交的原采购入库冻结证明。 */
@FeignClient(contextId = "remoteWmsPurchaseInboundPostingProofService", value = Constants.Name)
public interface RemoteWmsPurchaseInboundPostingProofService {

	/** 只返回实际已过账原单的冻结摘要，非 V1 或缺账时拒绝。 */
	@InternalCall(userTokenRequired = true)
	@GetMapping("/wmsPurchaseInBill/internal/pms-posting-proof/{wmsBillId}")
	R<String> proof(@PathVariable("wmsBillId") Long wmsBillId);
}
