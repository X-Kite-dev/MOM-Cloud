package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestContextDTO;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestCreateCommand;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestProof;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestReviewCommand;
import com.kitex.mom.mes.entity.MesChangePointEffectRetestEntity;
import com.kitex.mom.mes.service.MesChangePointEffectRetestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * MES效果复测与QMS可靠复开；所有内部证明也须真实用户令牌和双岗位权限。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesChangePointEffectRetest")
@Tag(name = "变化点改善效果复测", description = "MES关闭后复测与独立核验")
public class MesChangePointEffectRetestController {
    private final MesChangePointEffectRetestService service;

    @GetMapping("/context/{improvementId}")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<MesChangePointEffectRetestContextDTO> context(@PathVariable Long improvementId) {
        return R.ok(service.context(improvementId));
    }

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<Page<MesChangePointEffectRetestEntity>> page(@RequestParam Long improvementId,
                                                          @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.queryPage(improvementId, current, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<MesChangePointEffectRetestEntity> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/create")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:handle')")
    @Operation(summary = "冻结目标、关闭周期及权威报工/变化点证据")
    public R<MesChangePointEffectRetestEntity> create(@RequestBody MesChangePointEffectRetestCreateCommand command) {
        return R.ok(service.create(command));
    }

    @PostMapping("/review")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:verify') and @pms.hasPermission('capa:verify')")
    public R<MesChangePointEffectRetestEntity> review(@RequestBody MesChangePointEffectRetestReviewCommand command) {
        MesChangePointEffectRetestEntity record = service.review(command);
        // review代理事务已提交：QMS反查只能读到持久FAIL，不能以未提交请求触发新周期。
        if ("FAIL".equals(record.getStatus())) {
            service.executeReopen(record.getId());
        }
        return R.ok(service.detail(record.getId()));
    }

    @PostMapping("/{id}/reopen-retry")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:verify') and @pms.hasPermission('capa:verify')")
    public R<MesChangePointEffectRetestEntity> retry(@PathVariable Long id) {
        service.executeReopen(id);
        return R.ok(service.detail(id));
    }

    @Inner
    @GetMapping("/internal/{id}/proof")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:verify') and @pms.hasPermission('capa:verify')")
    @Operation(summary = "QMS反查已提交未达标事实，不回调QMS且不修改记录")
    public R<MesChangePointEffectRetestProof> proof(@PathVariable Long id) {
        return R.ok(service.proof(id));
    }
}
