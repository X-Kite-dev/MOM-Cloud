package com.kitex.mom.plan.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.plan.agent.service.OmsAgentProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 项目订单归属的权威业务入口，人工编制与审核分离。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/omsAgentProject")
public class OmsAgentProjectController {

    private final OmsAgentProjectService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('agent:project:view')")
    public R<?> page(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size,
                     @RequestParam(required = false) String projectCode, @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, projectCode, status));
    }

    @PostMapping("/page-query")
    @PreAuthorize("@pms.hasPermission('agent:project:view')")
    public R<?> pageQuery(@RequestBody PageQuery query) {
        return R.ok(service.page(query.current(), query.size(), query.projectCode(), query.status()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('agent:project:view')")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/detail-query")
    @PreAuthorize("@pms.hasPermission('agent:project:view')")
    public R<?> detailQuery(@RequestBody DetailQuery query) {
        return R.ok(service.detail(query.id()));
    }

    @PostMapping("/submit")
    @PreAuthorize("@pms.hasPermission('agent:project:edit')")
    public R<String> submit(@Valid @RequestBody OmsAgentProjectService.Submit command) throws Exception {
        return R.ok(String.valueOf(service.submit(command)));
    }

    @PostMapping("/review")
    @PreAuthorize("@pms.hasPermission('agent:project:review')")
    public R<?> review(@Valid @RequestBody OmsAgentProjectService.Review command) throws Exception {
        service.review(command);
        return R.ok(true);
    }

    @PostMapping("/retire")
    @PreAuthorize("@pms.hasPermission('agent:project:review')")
    public R<?> retire(@Valid @RequestBody OmsAgentProjectService.Retire command) throws Exception {
        service.retire(command);
        return R.ok(true);
    }

    @PostMapping("/query")
    @PreAuthorize("@pms.hasPermission('agent:project:view') and @pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<?> query(@Valid @RequestBody OmsAgentProjectService.Query command) {
        return R.ok(service.query(command));
    }

    public record PageQuery(long current, long size, String projectCode, String status) {
    }

    public record DetailQuery(Long id) {
    }
}
