package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesWipTimeoutPreviewDTO;
import com.kitex.mom.mes.service.impl.MesWipTimeoutPreviewService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 读取当前来源的草稿试算，无配置、事件或通知写入。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWipTimeoutConfig/preview")
@PreAuthorize(MesWipTimeoutPreviewService.READ)
public class MesWipTimeoutPreviewController {
    private final MesWipTimeoutPreviewService service;

    @GetMapping("/sources")
    public R<MesWipTimeoutPreviewDTO.Sources> sources(
            @RequestParam String barcode, @RequestParam(required = false) String afterId) {
        return R.ok(service.sources(barcode, afterId));
    }

    @PostMapping
    public R<MesWipTimeoutPreviewDTO.Preview> preview(
            @RequestBody MesWipTimeoutPreviewDTO.Query query) {
        return R.ok(service.preview(query));
    }
}
