package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.dto.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(contextId = "remoteMaterialReservationService", value = Constants.Name)
public interface RemoteMaterialReservationService {

    @PostMapping("/material/wmsMaterialReservation/reserve")
    R<WmsMaterialReservationResultDTO> reserve(@RequestBody WmsMaterialReservationCreateDTO command);

    @PostMapping("/material/wmsMaterialReservation/release")
    R<Boolean> release(@RequestBody WmsMaterialReservationReleaseDTO command);

    @PostMapping("/material/wmsMaterialReservation/reallocate")
    R<Boolean> reallocate(@RequestBody WmsMaterialReservationReallocateDTO command);

    @PostMapping("/material/wmsMaterialReservation/statuses")
    R<Map<Long, String>> statuses(@RequestBody WmsMaterialReservationStatusQueryDTO query);

    @PostMapping("/material/wmsMaterialReservation/ledgers")
    R<List<WmsMaterialReservationLedgerDTO>> ledgers(@RequestBody WmsMaterialReservationLedgerQueryDTO query);

    @PostMapping("/material/wmsMaterialReservation/compensate")
    R<WmsMaterialCompensationResultDTO> compensate(@RequestBody WmsMaterialCompensationCommandDTO command);
}
