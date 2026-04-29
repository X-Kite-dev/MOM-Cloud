package com.kitex.mom.common.flow.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.common.Constants;
import com.kitex.mom.common.flow.dto.StartFlowDataDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 远程流程适配器服务
 */
@FeignClient(contextId = "remoteProcessAdapterService", value = Constants.Name)
public interface RemoteProcessAdapterService {

    /**
     * 启动审批流程
     */
    @PostMapping("/process/startFlow")
    R<Boolean> startFlow(@RequestBody StartFlowDataDTO param);
}
