package com.kitex.mom.qms.agent.service;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.qms.agent.dto.QmsRootCauseImportDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/** 固定AI保存来源反查；仅显式引用时使用原人员令牌，不执行模型或启动探活。 */
@FeignClient(contextId = "remoteAiRootCauseProof", value = "${mom.ai.experience.service-id:kitex-mom-ai}")
public interface RemoteAiRootCauseProof {

	/** 读取指定已保存分析及原取证的权威证明；不可达时拒绝新引用。 */
	@InternalCall(userTokenRequired = true)
	@PostMapping("${mom.ai.experience.context-path:/ai}/agent/industry/quality/root-cause-proof")
	R<Map<String, Object>> proof(@RequestBody QmsRootCauseImportDTO.Source source);
}
