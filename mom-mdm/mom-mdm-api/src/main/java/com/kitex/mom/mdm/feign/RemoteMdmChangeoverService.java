package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmChangeoverCatalogDTO;
import com.kitex.mom.mdm.dto.MdmChangeoverCatalogQueryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(contextId = "remoteMdmChangeoverService", value = Constants.Name)
public interface RemoteMdmChangeoverService {
    @InternalCall
    @PostMapping("/mdmChangeoverRule/catalog")
    R<MdmChangeoverCatalogDTO> catalog(@RequestBody MdmChangeoverCatalogQueryDTO query);
}
