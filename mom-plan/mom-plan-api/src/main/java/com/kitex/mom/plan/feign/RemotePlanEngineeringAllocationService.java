package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.Constants;
import com.kitex.mom.plan.dto.PlnEngineeringAllocationDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * MES工程影响查询使用的实名PLAN只读客户端，保留原人员令牌与组织上下文。
 */
@FeignClient(contextId = "remotePlanEngineeringAllocationService", value = Constants.Name)
public interface RemotePlanEngineeringAllocationService {

	/**
	 * 只传原工单号；分配正文必须由PLAN向MES正式接口回读，不接收客户端证据。
	 */
	@InternalCall(userTokenRequired = true)
	@GetMapping("/engineering-allocation/internal/observation")
	R<PlnEngineeringAllocationDTO.Observation> observe(@RequestParam("taskCode") String taskCode);
}
