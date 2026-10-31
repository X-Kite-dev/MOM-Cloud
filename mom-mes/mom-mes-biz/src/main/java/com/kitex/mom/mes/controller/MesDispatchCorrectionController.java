package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesDispatchCorrectionDTO;
import com.kitex.mom.mes.service.impl.MesDispatchCorrectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 只接受班次及候选工位的具体目标，审批不允许夹带新值。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesDispatchCorrection")
public class MesDispatchCorrectionController {

    private final MesDispatchCorrectionService service;

    @GetMapping("/preview")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<MesDispatchCorrectionDTO.Preview> preview(@RequestParam String arrangeId, @RequestParam(required = false) String shiftCode, @RequestParam(required = false) String worksiteCodes) {
        return R.ok(service.preview(arrangeId, shiftCode, worksiteCodes));
    }

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<MesDispatchCorrectionDTO.Page> page(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) String status, @RequestParam(required = false) String arrangeId) {
        return R.ok(service.page(current, size, status, arrangeId));
    }

    @GetMapping("/{id:[0-9]+}")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<MesDispatchCorrectionDTO.Detail> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/requests/{requestId}")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<MesDispatchCorrectionDTO.Resolution> receipt(@PathVariable String requestId) {
        return R.ok(service.receipt(requestId));
    }

    @PostMapping("/commands")
    @SysLog("办理受控派工修正")
    @PreAuthorize("#command != null && ((#command.action == 'SUBMIT' && @pms.hasPermission('mesTaskArrange:arrange') && @pms.hasPermission('mesDispatchReturn:apply')) || ((#command.action == 'APPROVE' || #command.action == 'REJECT') && @pms.hasPermission('mesTaskArrange:arrange') && @pms.hasPermission('mesDispatchReturn:review')) || (#command.action == 'WITHDRAW' && @pms.hasPermission('mesDispatchReturn:apply')))")
    public R<MesDispatchCorrectionDTO.Resolution> command(@RequestBody MesDispatchCorrectionDTO.Command command) {
        return R.ok(service.command(command));
    }

    @PostMapping("/cancel-original")
    @SysLog("取消未受理派工修正原号")
    @PreAuthorize("#command != null && ((#command.action == 'SUBMIT' && @pms.hasPermission('mesTaskArrange:arrange') && @pms.hasPermission('mesDispatchReturn:apply')) || ((#command.action == 'APPROVE' || #command.action == 'REJECT') && @pms.hasPermission('mesTaskArrange:arrange') && @pms.hasPermission('mesDispatchReturn:review')) || (#command.action == 'WITHDRAW' && @pms.hasPermission('mesDispatchReturn:apply')))")
    public R<MesDispatchCorrectionDTO.Resolution> cancel(@RequestBody MesDispatchCorrectionDTO.Command command) {
        return R.ok(service.cancel(command));
    }
}
