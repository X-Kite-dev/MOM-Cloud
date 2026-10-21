package com.kitex.mom.qms.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;
import com.kitex.mom.qms.entity.QmsReworkOrderEntity;
import com.kitex.mom.qms.service.QmsReworkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/qmsReworkOrder")
public class QmsReworkOrderController {
    private final QmsReworkOrderService service;

    @PostMapping("/create")
    @PreAuthorize("@pms.hasPermission('procedureTask:handle') or @pms.hasPermission('productTask:handle')")
    public R<QmsReworkOrderEntity> create(@RequestParam String inspectionType, @RequestParam Long handleRecordId) {
        return R.ok(service.create(inspectionType, handleRecordId));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("@pms.hasPermission('procedureTask:handle') or @pms.hasPermission('productTask:handle')")
    public R<QmsReworkOrderEntity> retry(@PathVariable Long id) {
        return R.ok(service.retry(id));
    }

    @PostMapping("/{id}/result-sync/retry")
    @PreAuthorize("@pms.hasPermission('procedureTask:handle') or @pms.hasPermission('productTask:handle')")
    public R<Void> retryResultSync(@PathVariable Long id) {
        service.retryResultSync(id);
        return R.ok();
    }

    @Operation(summary = "MES返工完工幂等回传")
    @PostMapping("/mes-complete")
    public R<Long> mesComplete(@RequestBody QmsReworkCompletionDTO command) {
        return R.ok(service.completeFromMes(command));
    }

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('procedureTask:show') or @pms.hasPermission('productTask:show')")
    public R<IPage<QmsReworkOrderEntity>> page(Page<QmsReworkOrderEntity> page, QmsReworkOrderEntity query) {
        QueryWrapper<QmsReworkOrderEntity> wrapper = QueryGenerator.initQueryWrapper(query);
        wrapper.orderByDesc("initiated_time").orderByDesc("id");
        return R.ok(service.page(page, wrapper));
    }
}
