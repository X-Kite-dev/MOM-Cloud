package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.entity.MesIdentifierMappingEntity;
import com.kitex.mom.mes.service.MesIdentifierMappingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 临时码、替代码和外部码到规范生产标识的版本化治理。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesIdentifierMapping")
@Tag(name = "生产标识转换", description = "受控解析临时码、替代码、客户/供应商外码和历史码")
public class MesIdentifierMappingController {
    private final MesIdentifierMappingService service;

    @GetMapping("/page")
    @Operation(summary = "分页查询生产标识映射")
    @PreAuthorize("@pms.hasPermission('mesIdentifierMapping:show')")
    public R<IPage<MesIdentifierMappingEntity>> page(Page<MesIdentifierMappingEntity> page,
                                                     MesIdentifierMappingEntity query) {
        return R.ok(service.queryPage(page, query));
    }

    @PostMapping("/draft")
    @SysLog("保存生产标识映射草稿")
    @PreAuthorize("@pms.hasPermission('mesIdentifierMapping:edit')")
    public R<MesIdentifierMappingEntity> saveDraft(@RequestBody MesIdentifierMappingEntity draft) {
        return R.ok(service.saveDraft(draft));
    }

    @PostMapping("/copy")
    @SysLog("复制生产标识映射新版本")
    @PreAuthorize("@pms.hasPermission('mesIdentifierMapping:edit')")
    public R<MesIdentifierMappingEntity> copy(@RequestParam Long id) {
        return R.ok(service.copyVersion(id));
    }

    @PostMapping("/publish")
    @SysLog("发布生产标识映射")
    @PreAuthorize("@pms.hasPermission('mesIdentifierMapping:publish')")
    public R<MesIdentifierMappingEntity> publish(@RequestParam Long id) {
        return R.ok(service.publish(id));
    }

    @PostMapping("/retire")
    @SysLog("退役生产标识映射")
    @PreAuthorize("@pms.hasPermission('mesIdentifierMapping:publish')")
    public R<MesIdentifierMappingEntity> retire(@RequestParam Long id) {
        return R.ok(service.retire(id));
    }
}
