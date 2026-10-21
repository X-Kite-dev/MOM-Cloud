package com.kitex.mom.mdm.sop;

import com.kitex.common.core.constant.ServiceNameConstants;
import com.kitex.common.feign.annotation.InternalCall;
import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 独立窄合同，不复用跳过租户校验的历史公开下载接口。 */
@FeignClient(contextId = "mdmSopLegacyFileClient", value = ServiceNameConstants.ADMIN_SERVICE)
public interface MdmSopLegacyFileClient {
    @InternalCall(userTokenRequired = true)
    @GetMapping("/sys-file/internal/sop/original")
    Response original(@RequestParam("fileName") String fileName);
}
