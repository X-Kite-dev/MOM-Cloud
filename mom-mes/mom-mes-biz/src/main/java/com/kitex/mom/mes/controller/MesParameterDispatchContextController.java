package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesParameterDispatchContextDTO;
import com.kitex.mom.mes.service.impl.MesParameterDispatchContextService;
import com.kitex.mom.mes.service.impl.MesParameterPreparationOptionsService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/process-parameters/internal")
public class MesParameterDispatchContextController {
    private final MesParameterDispatchContextService service;
    private final MesParameterPreparationOptionsService options;

    @Inner
    @PostMapping("/dispatch-context")
    @PreAuthorize(
            "@pms.hasPermission('mesTask:show') and @pms.hasPermission('mesProduceTask:show')")
    public R<MesParameterDispatchContextDTO.Evidence> context(
            @Valid @RequestBody MesParameterDispatchContextDTO.Query query) {
        return R.ok(service.query(query));
    }

    @Inner
    @PostMapping("/preparation-options")
    @PreAuthorize(
            "@pms.hasPermission('mesTask:show') and @pms.hasPermission('mesProduceTask:show')")
    public R<MesParameterDispatchContextDTO.Options> options(
            @Valid @RequestBody MesParameterDispatchContextDTO.OptionsQuery query) {
        return R.ok(options.query(query));
    }
}
