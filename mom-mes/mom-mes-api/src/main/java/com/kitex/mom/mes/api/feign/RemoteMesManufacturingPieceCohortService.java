package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesManufacturingPieceCohortDTO.*;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/**
 * QMS沿实际人员和工厂上下文读取MES原工程节点及完整登记总体。
 */
@FeignClient(contextId = "remoteMesManufacturingPieceCohortService", value = Constants.Name)
public interface RemoteMesManufacturingPieceCohortService {
	/**
	 * 沿原人员令牌读取实际工厂精确工单的原冻结工程节点；无授权或原件损坏时返回失败。
	 */
	@InternalCall
	@GetMapping("/manufacturing-piece-cohort/internal/context")
	R<Context> context(@RequestParam("orderCode") String orderCode);

	/**
	 * 沿原人员令牌读取精确工单和UTC半开登记周期的完整制造身份总体，超过1000件或原件损坏时拒绝。
	 *
	 * @param query 原工单、稳定检验节点与UTC登记周期
	 * @return 完整成员、集合指纹及MES观察时间
	 */
	@InternalCall
	@PostMapping("/manufacturing-piece-cohort/internal/read")
	R<View> inspect(@RequestBody Query query);
}
