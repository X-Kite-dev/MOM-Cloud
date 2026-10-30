package com.kitex.mom.common.message.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.core.constant.CommonConstants;
import org.springframework.web.bind.annotation.RequestHeader;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.common.Constants;
import com.kitex.mom.common.message.dto.MessageBusinessDTO;
import com.kitex.mom.common.message.dto.MessageDTO;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 远程消息推送服务
 */
@FeignClient(contextId = "remoteMessagePushService", value = Constants.Name)
public interface RemoteMessagePushService {

	/**
	 * 业务模块消息推送
	 *
	 * @param messageBusinessDTO 业务消息发送DTO
	 */
	@NoToken
	@PostMapping("/push/businessPush")
	R<Boolean> businessPush(@RequestBody @Valid MessageBusinessDTO messageBusinessDTO);

	/** 非Web任务显式传递租户及调用工厂上下文；当前消息配置是租户级。返回接收结果，不代表渠道送达。 */
	@NoToken
	@PostMapping("/push/businessPush")
	R<Boolean> businessPushScoped(@RequestHeader(CommonConstants.TENANT_ID) Long tenantId,
			@RequestHeader(CommonConstants.FACTORY_CODE) String factoryCode,
			@RequestBody @Valid MessageBusinessDTO messageBusinessDTO);

	/**
	 * 消息推送给个人
	 */
	@NoToken
	@PostMapping("/push/pushPersonMsg")
	R<Boolean> pushPersonMsg(@RequestBody @Valid MessageDTO messageDTO);

}
