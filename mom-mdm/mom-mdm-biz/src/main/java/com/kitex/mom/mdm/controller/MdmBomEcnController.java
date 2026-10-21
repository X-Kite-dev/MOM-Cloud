package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.dto.MdmBomEcnCommand;
import com.kitex.mom.mdm.service.impl.MdmBomEcnService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmBomEcn")
public class MdmBomEcnController {
    private final MdmBomEcnService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_bomEcn_view')")
    public R<?> page(@RequestParam String factoryCode, @RequestParam(defaultValue = "1") long current,
                     @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.page(factoryCode, current, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_bomEcn_view')")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/{id}/operations")
    @PreAuthorize("@pms.hasPermission('mdm_bomEcn_view')")
    public R<?> operations(@PathVariable Long id) {
        return R.ok(service.operations(id));
    }

    @GetMapping("/{id}/review")
    @PreAuthorize("@pms.hasPermission('mdm_bomEcn_view')")
    public R<?> review(@PathVariable Long id) {
        return R.ok(service.review(id));
    }

    @PostMapping("/sign")
    @SysLog("BOM工程变更多岗位会签")
    @PreAuthorize("@pms.hasPermission('mdm_bomEcn_view')")
    public R<?> sign(@RequestBody MdmBomEcnCommand command) {
        return R.ok(service.reviewAction(command, "SIGN"));
    }

    @PostMapping("/veto")
    @SysLog("BOM工程变更岗位否决")
    @PreAuthorize("@pms.hasPermission('mdm_bomEcn_view')")
    public R<?> veto(@RequestBody MdmBomEcnCommand command) {
        return R.ok(service.reviewAction(command, "VETO"));
    }

    @GetMapping("/preview/{targetBomId}")
    @PreAuthorize("@pms.hasPermission('mdm_bomEcn_view')")
    public R<?> preview(@PathVariable Long targetBomId) {
        return R.ok(service.preview(targetBomId));
    }

    @PostMapping("/submit")
    @SysLog("提交BOM工程变更")
    @PreAuthorize("@pms.hasPermission('mdm_bomEcn_submit')")
    public R<?> submit(@RequestBody MdmBomEcnCommand command) {
        return R.ok(service.submit(command));
    }

    @PostMapping("/publish")
    @SysLog("异岗复核发布BOM工程变更")
    @PreAuthorize("@pms.hasPermission('mdm_bomEcn_review')")
    public R<?> publish(@RequestBody MdmBomEcnCommand command) {
        return R.ok(service.act(command, "PUBLISH"));
    }

    @PostMapping("/reject")
    @SysLog("驳回BOM工程变更")
    @PreAuthorize("@pms.hasPermission('mdm_bomEcn_review')")
    public R<?> reject(@RequestBody MdmBomEcnCommand command) {
        return R.ok(service.act(command, "REJECT"));
    }

    @PostMapping("/withdraw")
    @SysLog("撤回BOM工程变更")
    @PreAuthorize("@pms.hasPermission('mdm_bomEcn_submit')")
    public R<?> withdraw(@RequestBody MdmBomEcnCommand command) {
        return R.ok(service.act(command, "WITHDRAW"));
    }
}
