package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.qms.entity.QmsInspectionEventOutboxEntity;
import com.kitex.mom.qms.service.impl.QmsInspectionEventDeliveryService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 沿既有工序历史/处置权限查询和恢复，服务仍核实际MDM工厂read/write。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection/procedure/task/history/events/delivery")
public class QmsInspectionEventDeliveryController {
    private final QmsInspectionEventDeliveryService service;

    @GetMapping
    @PreAuthorize("@pms.hasPermission('procedureTask:history')")
    public R<List<QmsInspectionEventOutboxEntity>> status(@RequestParam("taskId") Long taskId) {
        return R.ok(service.status(taskId));
    }

    @PostMapping("/retry")
    @PreAuthorize("@pms.hasPermission('procedureTask:handle')")
    @SysLog("重新排队普通工序检验原件")
    public R<Boolean> retry(@RequestParam("taskId") Long taskId) {
        return R.ok(service.retry(taskId));
    }
}
