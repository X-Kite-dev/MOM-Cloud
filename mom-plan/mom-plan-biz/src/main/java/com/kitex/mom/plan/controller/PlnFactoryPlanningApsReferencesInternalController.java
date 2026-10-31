package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningApsReferencesDTO;
import com.kitex.mom.plan.service.impl.PlnFactoryPlanningApsReferencesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/factoryPlanningImpact/internal")
public class PlnFactoryPlanningApsReferencesInternalController {
    private final PlnFactoryPlanningApsReferencesService service;

    @Inner
    @GetMapping("/apsReferences")
    public R<MdmFactoryPlanningApsReferencesDTO> read(@RequestParam String factoryCode) {
        return R.ok(service.read(factoryCode));
    }
}
