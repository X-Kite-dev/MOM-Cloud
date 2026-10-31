package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.service.PlnWarehouseLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/plnWarehouseLifecycle")
public class PlnWarehouseLifecycleController {

    private final PlnWarehouseLifecycleService service;

    @Inner
    @GetMapping("/references")
    public R<List<String>> references(@RequestParam String warehouseCode) {
        return R.ok(service.references(warehouseCode));
    }
}
