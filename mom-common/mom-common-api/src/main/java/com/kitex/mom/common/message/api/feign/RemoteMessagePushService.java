package com.kitex.mom.common.message.api.feign;

import com.kitex.common.core.util.R;
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

    /**
     * 消息推送给个人
     */
    @NoToken
    @PostMapping("/push/pushPersonMsg")
    R<Boolean> pushPersonMsg(@RequestBody @Valid MessageDTO messageDTO);

}
