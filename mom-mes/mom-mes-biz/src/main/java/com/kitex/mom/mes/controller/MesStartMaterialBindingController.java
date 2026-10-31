package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesStartMaterialBindingDTO;
import com.kitex.mom.mes.service.impl.MesStartMaterialBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 首次批次投料来源绑定；执行始终调用原实际开工事务。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesStartMaterialBinding")
@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
public class MesStartMaterialBindingController {
    private final MesStartMaterialBindingService service;

    @GetMapping("/preparation")
    public R<?> preparation(@RequestParam String arrangeId) {
        return R.ok(service.preparation(arrangeId));
    }

    @PostMapping("/preview")
    public R<?> preview(@RequestBody MesStartMaterialBindingDTO.Query query) {
        return R.ok(service.preview(query));
    }

    @PostMapping("/execute")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:start')")
    public R<?> execute(@RequestBody String raw) {
        return R.ok(service.execute(raw));
    }

    @PostMapping("/cancel-original")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:start')")
    public R<?> cancel(@RequestBody String raw) {
        return R.ok(service.cancel(raw));
    }

    @GetMapping("/requests/{requestId}")
    public R<?> request(@PathVariable String requestId) {
        return R.ok(service.request(requestId));
    }

    @GetMapping("/arrangements/{arrangeId}")
    public R<?> arrangement(@PathVariable String arrangeId) {
        return R.ok(service.arrangement(arrangeId));
    }
}
