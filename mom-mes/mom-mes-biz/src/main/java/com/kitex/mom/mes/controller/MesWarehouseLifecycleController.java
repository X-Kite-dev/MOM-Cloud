package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.service.MesWarehouseLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehouseLifecycle")
public class MesWarehouseLifecycleController {

    private final MesWarehouseLifecycleService service;

    @Inner
    @GetMapping("/references")
    public R<List<String>> references(@RequestParam String warehouseCode, @RequestParam Long warehouseId) {
        return R.ok(service.references(warehouseCode, warehouseId));
    }
}
