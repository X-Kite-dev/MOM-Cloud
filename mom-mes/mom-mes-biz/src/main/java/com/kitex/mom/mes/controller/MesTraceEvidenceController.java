package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.service.impl.MesTraceEvidenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTraceEvidence")
public class MesTraceEvidenceController {
    private final MesTraceEvidenceService service;

    @GetMapping("/page")
    public R<?> search(@RequestParam String kind, @RequestParam String code, @RequestParam(required = false) String batch, @RequestParam(defaultValue = "1") long current) {
        return R.ok(service.search(kind, code, batch, current));
    }

    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/{id}/materials")
    public R<?> materials(@PathVariable Long id, @RequestParam String kind, @RequestParam(defaultValue = "1") long current) {
        return R.ok(service.materials(id, kind, current));
    }

    @GetMapping("/{id}/packages")
    public R<?> packages(@PathVariable Long id, @RequestParam(defaultValue = "1") long current) {
        return R.ok(service.packages(id, current));
    }

    @GetMapping("/{id}/events")
    public R<?> events(@PathVariable Long id, @RequestParam(defaultValue = "1") long current) {
        return R.ok(service.events(id, current));
    }
}
