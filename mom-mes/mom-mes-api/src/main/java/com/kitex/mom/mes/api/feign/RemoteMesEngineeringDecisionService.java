package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesPlanEngineeringDecisionDTO.Cancel;
import com.kitex.mom.mes.dto.MesPlanEngineeringDecisionDTO.Receipt;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * PLAN核对或撤销原准备时读取MES实际永久决策，保留原用户与工厂权限，不接受前端成功证明。
 */
@FeignClient(contextId = "remoteMesEngineeringDecisionService", value = Constants.Name)
public interface RemoteMesEngineeringDecisionService {

	/**
	 * 精确原号读取；null仍表示未知，不允许据此释放PLAN来源。
	 */
	@InternalCall(userTokenRequired = true)
	@GetMapping("/plan-engineering-decision/internal/request")
	R<Receipt> request(@RequestParam("requestId") String requestId);

	/**
	 * 与采用同一决策锁；APPLIED先赢则只返回实际APPLIED，不能补写取消。
	 */
	@InternalCall(userTokenRequired = true)
	@PostMapping("/plan-engineering-decision/internal/cancel")
	R<Receipt> cancel(@RequestBody Cancel command);
}
