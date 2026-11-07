package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesInspectionFirstRoundDTO.*;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/**
 * QMS沿原人员身份调用MES永久首轮归属接口，不以内部标记代替人员或工厂授权。
 */
@FeignClient(contextId = "remoteMesInspectionFirstRoundService", value = Constants.Name)
public interface RemoteMesInspectionFirstRoundService {
	/**
	 * 沿原采集请求申请并永久核对唯一首轮归属；同号异事实或原组织不符时拒绝。
	 */
	@InternalCall
	@PostMapping("/inspection-first-round/internal/claim")
	R<View> claim(@RequestBody Claim claim);
}
