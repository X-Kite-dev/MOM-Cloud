package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesMaterialBindingSupplementDTO;
import com.kitex.mom.mes.service.impl.MesMaterialBindingSupplementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 暂停批次的投料来源追加确认；确认不复工、不新增库存或产量。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesMaterialBindingSupplement")
@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
public class MesMaterialBindingSupplementController {
    private final MesMaterialBindingSupplementService service;

    @GetMapping("/preparation")
    public R<?> preparation(@RequestParam String arrangeId) {
        return R.ok(service.preparation(arrangeId));
    }

    @PostMapping("/preview")
    public R<?> preview(@RequestBody MesMaterialBindingSupplementDTO.Query query) {
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
