package com.kitex.mom.qms.agent.service;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 固定服务/路径反查真实发布谱系；不能从模型或页面传入URL、鉴权头。
 */
@FeignClient(contextId = "remoteAiExperienceProof", value = "${mom.ai.experience.service-id:kitex-mom-ai}")
public interface RemoteAiExperienceProof {
    @InternalCall(userTokenRequired = true)
    @PostMapping("${mom.ai.experience.context-path:/ai}/agent/quality/8d/experiences/internal/reference-proof")
    R<Map<String, Object>> proof(@RequestBody Map<String, Object> command);
}
