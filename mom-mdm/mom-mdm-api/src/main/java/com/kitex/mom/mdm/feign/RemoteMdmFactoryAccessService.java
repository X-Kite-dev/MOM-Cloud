package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(contextId = "remoteMdmFactoryAccessService", value = Constants.Name)
public interface RemoteMdmFactoryAccessService {
    @NoToken
    @PostMapping("/mdmFactoryAccess/internal/check")
    R<Boolean> check(@RequestBody MdmFactoryAccessCheck request);
}
