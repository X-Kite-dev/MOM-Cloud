package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.qms.dto.req.QmsBadHandleReq;
import com.kitex.mom.qms.dto.req.QmsMrbCommand;
import com.kitex.mom.qms.service.impl.QmsMrbService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/bad/mrb")
public class QmsMrbController {
    private final QmsMrbService service;

    @GetMapping("/detail/{id}")
    @PreAuthorize("@pms.hasPermission('bad:show')")
    public R<?> context(@PathVariable Long id, @RequestParam(defaultValue = "1") long page) {
        return R.ok(service.context(id, page));
    }

    @GetMapping("/proposal/{id}")
    @PreAuthorize("@pms.hasPermission('bad:show')")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/preview")
    @PreAuthorize("@pms.hasPermission('bad:show')")
    public R<?> preview(@RequestBody QmsBadHandleReq req) {
        return R.ok(service.preview(req));
    }

    @PostMapping("/propose")
    @SysLog("提交MRB多岗位会签提案")
    @PreAuthorize("@pms.hasPermission('bad:handle')")
    public R<?> propose(@RequestBody QmsMrbCommand c) {
        c.setOperation("PROPOSE");
        return R.ok(service.execute(c));
    }

    @PostMapping("/sign")
    @SysLog("MRB独立岗位签署")
    @PreAuthorize("@pms.hasPermission('bad:show')")
    public R<?> sign(@RequestBody QmsMrbCommand c) {
        c.setOperation("SIGN");
        return R.ok(service.execute(c));
    }

    @PostMapping("/reject")
    @SysLog("否决未发布MRB提案")
    @PreAuthorize("@pms.hasPermission('bad:show')")
    public R<?> reject(@RequestBody QmsMrbCommand c) {
        c.setOperation("REJECT");
        return R.ok(service.execute(c));
    }

    @PostMapping("/withdraw")
    @SysLog("撤回未发布MRB提案")
    @PreAuthorize("@pms.hasPermission('bad:handle')")
    public R<?> withdraw(@RequestBody QmsMrbCommand c) {
        c.setOperation("WITHDRAW");
        return R.ok(service.execute(c));
    }
}
