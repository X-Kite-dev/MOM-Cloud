package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyCatalogDTO;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyCatalogQueryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * PLAN读取MDM正式生产批量策略的内部服务契约。
 */
@FeignClient(contextId = "remoteMdmProductionLotPolicyService", value = Constants.Name)
public interface RemoteMdmProductionLotPolicyService {
    @InternalCall
    @PostMapping("/mdmProductionLotPolicy/catalog")
    R<MdmProductionLotPolicyCatalogDTO> catalog(@RequestBody MdmProductionLotPolicyCatalogQueryDTO query);
}
