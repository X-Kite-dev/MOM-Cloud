package com.kitex.mom.mes.controller;

import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesMaterialReceiptCommand;
import com.kitex.mom.mes.service.impl.MesMaterialReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesMaterialReceipt")
public class MesMaterialReceiptController {
    private final MesMaterialReceiptService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mes_materialReceipt_view')")
    public R<?> page(SearchPage page, @RequestParam String factoryCode,
                     @RequestParam(required = false) String billNo, @RequestParam(required = false) String status) {
        return R.ok(service.page(page, factoryCode, billNo, status));
    }

    @GetMapping("/picking/{id}")
    @PreAuthorize("@pms.hasPermission('mes_materialReceipt_view')")
    public R<?> notices(@PathVariable Long id) {
        return R.ok(service.notices(id));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mes_materialReceipt_view')")
    public R<?> notice(@PathVariable Long id) {
        return R.ok(service.notice(id));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("@pms.hasPermission('mes_materialReceipt_view')")
    public R<?> history(@PathVariable Long id) {
        return R.ok(service.history(id));
    }

    @GetMapping("/{id}/sites")
    @PreAuthorize("@pms.hasPermission('mes_materialReceipt_view')")
    public R<?> sites(@PathVariable Long id) {
        return R.ok(service.sites(id));
    }

    @PostMapping("/receive")
    @SysLog("线边物料按批签收")
    @PreAuthorize("@pms.hasPermission('mes_materialReceipt_receive')")
    public R<?> receive(@RequestBody MesMaterialReceiptCommand command) {
        return R.ok(service.receive(command));
    }

    @PostMapping("/reverse")
    @SysLog("异岗复核冲销线边签收")
    @PreAuthorize("@pms.hasPermission('mes_materialReceipt_reverse')")
    public R<?> reverse(@RequestBody MesMaterialReceiptCommand command) {
        return R.ok(service.reverse(command));
    }
}
