package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesProductionTimeDTO.Query;
import com.kitex.mom.mes.dto.MesProductionTimeDTO.Result;
import com.kitex.mom.mes.service.impl.MesProductionTimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/production-time")
public class MesProductionTimeController {

    private final MesProductionTimeService service;

    @GetMapping("/statistics")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<Result> statistics(Query query) {
        return R.ok(service.statistics(query));
    }
}
