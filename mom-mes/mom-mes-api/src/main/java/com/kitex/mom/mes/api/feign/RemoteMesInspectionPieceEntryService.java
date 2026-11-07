package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesInspectionPieceEntryDTO.*;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(contextId = "remoteMesInspectionPieceEntryService", value = Constants.Name)
public interface RemoteMesInspectionPieceEntryService {
    @InternalCall
    @PostMapping("/inspection-piece-entry/internal/query")
    R<View> query(@RequestBody Query query);
}
