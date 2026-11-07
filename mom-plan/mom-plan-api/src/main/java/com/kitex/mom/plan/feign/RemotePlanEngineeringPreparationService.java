package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.Constants;
import com.kitex.mom.plan.dto.PlnEngineeringPreparationDTO.*;
import com.kitex.mom.plan.dto.PlnEngineeringResolutionDTO.Certificate;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * MES原入口代理PLAN准备业务，保留原人员令牌；不绕过PLAN权限和实际工厂授权。
 */
@FeignClient(contextId = "remotePlanEngineeringPreparationService", value = Constants.Name)
public interface RemotePlanEngineeringPreparationService {

	/**
	 * 结构就绪状态与最近原记录，未安装不当作没有业务。
	 */
	@InternalCall(userTokenRequired = true)
	@GetMapping("/engineering-preparation/internal/context")
	R<Context> context(@RequestParam("taskCode") String taskCode);

	/**
	 * 精确原号回读，null表示未确认受理，不等于可换号。
	 */
	@InternalCall(userTokenRequired = true)
	@GetMapping("/engineering-preparation/internal/request")
	R<Receipt> request(@RequestParam("requestId") String requestId);

	/**
	 * 保护原PLAN来源，任何回执均不代表MES采用。
	 */
	@InternalCall(userTokenRequired = true)
	@PostMapping("/engineering-preparation/internal/prepare")
	R<Receipt> prepare(@RequestBody Command command);

	/**
	 * 原号撤销或未受理号永久封存。
	 */
	@InternalCall(userTokenRequired = true)
	@PostMapping("/engineering-preparation/internal/cancel")
	R<Receipt> cancel(@RequestBody CancelCommand command);

	/**
	 * 原号权威身份，包含不可变原MES主键、产品和完整映射摘要。
	 */
	@InternalCall(userTokenRequired = true)
	@GetMapping("/engineering-preparation/internal/certificate")
	R<Certificate> certificate(@RequestParam("requestId") String requestId);

	/**
	 * 只传原号，PLAN自行回读MES实际永久终态后确认；未知结果不释放。
	 */
	@InternalCall(userTokenRequired = true)
	@PostMapping("/engineering-preparation/internal/finish")
	R<Receipt> finish(@RequestParam("requestId") String requestId);
}
