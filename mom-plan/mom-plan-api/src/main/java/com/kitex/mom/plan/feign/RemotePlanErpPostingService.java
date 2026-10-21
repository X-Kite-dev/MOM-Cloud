package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.Constants;
import com.kitex.mom.plan.dto.PlnErpPostingEnvelope;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** MES 将已提交执行事实幂等转交 PLAN ERP 投递账的内部客户端。 */
@FeignClient(contextId = "remotePlanErpPostingService", value = Constants.Name)
public interface RemotePlanErpPostingService {
    @InternalCall
    @PostMapping("/erpPosting/internal/enqueue")
    R<String> enqueue(@RequestBody PlnErpPostingEnvelope envelope);
}
