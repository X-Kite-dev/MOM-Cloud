package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesInspectionRevisionExecutionDTO;
import com.kitex.mom.mes.dto.MesInspectionRevisionExecutionReceiptDTO;
import com.kitex.mom.mes.service.impl.MesInspectionRevisionExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/internal/inspection-revision-execution")
public class MesInspectionRevisionExecutionController {
    private final MesInspectionRevisionExecutionService service;

    @Inner
    @PostMapping
    public R<MesInspectionRevisionExecutionReceiptDTO> receive(
            @RequestBody MesInspectionRevisionExecutionDTO command) {
        return R.ok(service.receive(command));
    }
}
