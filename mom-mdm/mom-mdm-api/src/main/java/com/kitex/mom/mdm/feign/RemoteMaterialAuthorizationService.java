package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmMaterialAuthorizationEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

@FeignClient(contextId = "remoteMaterialAuthorizationService", value = Constants.Name)
public interface RemoteMaterialAuthorizationService {
    @InternalCall
    @GetMapping("/mdmMaterialAuthorization/active")
    R<List<MdmMaterialAuthorizationEntity>> active(
            @RequestParam("authorizationType") String authorizationType,
            @RequestParam("materialCode") String materialCode,
            @RequestParam(value = "partnerCode", required = false) String partnerCode,
            @RequestParam(value = "bizDate", required = false) LocalDate bizDate);
}
