package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesWipAgingDTO.Details;
import com.kitex.mom.mes.dto.MesWipAgingDTO.Query;
import com.kitex.mom.mes.dto.MesWipAgingDTO.Statistics;
import com.kitex.mom.mes.service.impl.MesWipAgingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前工序任务在制和自然停留下钻；客户端不能指定历史观察时点。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWipAging")
public class MesWipAgingController {

    private final MesWipAgingService service;

    @GetMapping("/statistics")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<Statistics> statistics(Query query) {
        return R.ok(service.statistics(query));
    }

    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<Details> details(Query query) {
        return R.ok(service.details(query));
    }
}
