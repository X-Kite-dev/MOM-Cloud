package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmDurationCalibrationDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(contextId = "remoteMdmDurationCalibrationService", value = Constants.Name)
public interface RemoteMdmDurationCalibrationService {
    @NoToken
    @PostMapping("/mdmDurationCalibration/internal/catalog")
    R<MdmDurationCalibrationDTO.Catalog> catalog(@RequestBody MdmDurationCalibrationDTO.CatalogQuery query);
}
