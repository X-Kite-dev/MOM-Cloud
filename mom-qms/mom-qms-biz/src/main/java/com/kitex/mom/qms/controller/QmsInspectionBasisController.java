package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.service.impl.QmsInspectionBasisService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 检验依据按需只读查询，沿用本类型任务查看权限。 */
@RestController
@RequestMapping("/inspection/basis")
@RequiredArgsConstructor
public class QmsInspectionBasisController {
    private final QmsInspectionBasisService basis;

    @GetMapping("/product")
    @PreAuthorize("@pms.hasPermission('productTask:show')")
    public R<QmsInspectionBasisService.View> product(@RequestParam Long taskId) {
        return R.ok(basis.view("PRODUCT", taskId));
    }

    @GetMapping("/procedure")
    @PreAuthorize("@pms.hasPermission('procedureTask:show')")
    public R<QmsInspectionBasisService.View> procedure(@RequestParam Long taskId) {
        return R.ok(basis.view("PROCEDURE", taskId));
    }
}
