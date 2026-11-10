package com.kitex.mom.mes.controller;

import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesMaterialReceiptCommand;
import com.kitex.mom.mes.exception.MesMaterialReceiptDestinationRejectedException;
import com.kitex.mom.mes.service.impl.MesMaterialReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 线边物料签收工作台入口，提供通知查询、分批签收和异岗冲销。
 */
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
    @PreAuthorize("@pms.hasPermission('mes_materialReceipt_view') or @pms.hasPermission('mes_materialReceipt_receive')")
    public R<?> sites(@PathVariable Long id) {
        return R.ok(service.sites(id));
    }

    /**
     * 按原通知和实际配送工位签收入账。
     *
     * @param command 带原请求号、工位及预期累计量的签收指令
     * @return 签收回执，或确认工位不匹配时的明确业务拒绝
     */
    @PostMapping("/receive")
    @SysLog("线边物料按批签收")
    @PreAuthorize("@pms.hasPermission('mes_materialReceipt_receive')")
    public R<?> receive(@RequestBody MesMaterialReceiptCommand command) {
        try {
            return R.ok(service.receive(command));
        } catch (MesMaterialReceiptDestinationRejectedException rejection) {
            // 签收事务退出后再返回明确拒绝，客户端可区分首次工位选错与结果未知。
            return R.failed(rejection.getMessage());
        }
    }

    @PostMapping("/reverse")
    @SysLog("异岗复核冲销线边签收")
    @PreAuthorize("@pms.hasPermission('mes_materialReceipt_reverse')")
    public R<?> reverse(@RequestBody MesMaterialReceiptCommand command) {
        return R.ok(service.reverse(command));
    }
}
