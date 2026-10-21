package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesChangePointImprovementCreateCommand;
import com.kitex.mom.mes.entity.MesChangePointImprovementEntity;
import com.kitex.mom.mes.entity.MesChangePointImprovementOperationEntity;
import com.kitex.mom.mes.service.MesChangePointImprovementService;
import com.kitex.mom.mes.service.impl.MesChangePointImprovementCoordinator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 变化点损失Pareto转CAPA/8D的可靠来源工作台。
 */
@RestController
@RequestMapping("/mesChangePointImprovement")
@RequiredArgsConstructor
@Tag(name = "变化点改善", description = "变化点损失Pareto与QMS CAPA/8D闭环")
public class MesChangePointImprovementController {

    private final MesChangePointImprovementService service;
    private final MesChangePointImprovementCoordinator coordinator;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<IPage<MesChangePointImprovementEntity>> page(Page<MesChangePointImprovementEntity> page,
                                                          MesChangePointImprovementEntity query) {
        return R.ok(service.queryPage(page, query));
    }

    @Operation(summary = "冻结Pareto基线并发起QMS CAPA")
    @PostMapping("/create")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:handle')")
    public R<MesChangePointImprovementEntity> create(
            @RequestBody MesChangePointImprovementCreateCommand command) {
        MesChangePointImprovementEntity record = service.create(command);
        if (record.getQmsCapaId() == null && "PENDING".equals(record.getLinkStatus())) {
            coordinator.attempt(record.getId());
        }
        return R.ok(service.getDetail(record.getId()));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:handle')")
    public R<Void> retry(@PathVariable Long id) {
        coordinator.retry(id);
        return R.ok();
    }

    @PostMapping("/{id}/refresh")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<Void> refresh(@PathVariable Long id) {
        coordinator.refresh(id);
        return R.ok();
    }

    @GetMapping("/{id}/operations")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<List<MesChangePointImprovementOperationEntity>> operations(@PathVariable Long id) {
        return R.ok(service.operations(id));
    }
}
