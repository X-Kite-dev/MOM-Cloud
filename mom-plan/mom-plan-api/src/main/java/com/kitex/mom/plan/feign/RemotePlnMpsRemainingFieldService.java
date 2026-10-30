package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.Constants;
import com.kitex.mom.plan.dto.PlnMpsRemainingFieldDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 现场只读摘要契约；只凭真实工单反查来源，不提供计划查询或审批能力。
 */
@FeignClient(contextId = "remotePlnMpsRemainingFieldService", value = Constants.Name)
public interface RemotePlnMpsRemainingFieldService {
    @InternalCall(userTokenRequired = true)
    @PostMapping("/plnMpsRemainingField/internal/summary")
    R<PlnMpsRemainingFieldDTO.Summary> summary(@RequestBody PlnMpsRemainingFieldDTO.Query query);
}
