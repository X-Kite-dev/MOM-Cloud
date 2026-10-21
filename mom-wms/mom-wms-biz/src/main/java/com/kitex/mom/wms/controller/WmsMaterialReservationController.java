package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.*;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/material/wmsMaterialReservation")
public class WmsMaterialReservationController {
    private final WmsMaterialReservationService reservationService;

    @PostMapping("/reserve")
    public R<WmsMaterialReservationResultDTO> reserve(@RequestBody WmsMaterialReservationCreateDTO command) {
        return R.ok(reservationService.reserve(command));
    }

    @PostMapping("/release")
    public R<Boolean> release(@RequestBody WmsMaterialReservationReleaseDTO command) {
        return R.ok(reservationService.release(command));
    }

    @PostMapping("/reallocate")
    public R<Boolean> reallocate(@RequestBody WmsMaterialReservationReallocateDTO command) {
        return R.ok(reservationService.reallocate(command));
    }

    @PostMapping("/statuses")
    public R<Map<Long, String>> statuses(@RequestBody WmsMaterialReservationStatusQueryDTO query) {
        return R.ok(reservationService.statuses(query.getSourceType(), query.getSourceIds()));
    }

    @PostMapping("/ledgers")
    public R<List<WmsMaterialReservationLedgerDTO>> ledgers(@RequestBody WmsMaterialReservationLedgerQueryDTO query) {
        return R.ok(reservationService.ledgers(query == null ? null : query.getReservationNos()));
    }

    @PostMapping("/compensate")
    public R<WmsMaterialCompensationResultDTO> compensate(@RequestBody WmsMaterialCompensationCommandDTO command) {
        return R.ok(reservationService.compensate(command));
    }
}
