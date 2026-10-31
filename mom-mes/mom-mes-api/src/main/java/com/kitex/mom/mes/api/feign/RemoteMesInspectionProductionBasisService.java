package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(contextId = "remoteMesInspectionProductionBasisService", value = Constants.Name)
public interface RemoteMesInspectionProductionBasisService {
    @InternalCall
    @PostMapping("/inspection-production-basis/internal/query")
    R<MesInspectionProductionBasisDTO.Evidence> query(
            @RequestBody MesInspectionProductionBasisDTO.Query query);
}
