package com.kitex.mom.mdm.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmProcessParameterStandardDTO;
import com.kitex.mom.mdm.entity.MdmProcessParameterStandardEntity;
import com.kitex.mom.mdm.service.MdmProcessParameterStandardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

/**
 * 工艺参数标准的版本化维护与发布入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmProcessParameterStandard")
public class MdmProcessParameterStandardController {

    private final MdmProcessParameterStandardService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_view')")
    public R<Page<MdmProcessParameterStandardEntity>> page(
            SearchPage page, @ParameterObject MdmProcessParameterStandardEntity query) {
        return R.ok(service.page(page.toMpPage(),
                new LambdaQueryWrapper<MdmProcessParameterStandardEntity>()
                        .like(StrUtil.isNotBlank(query.getStandardNo()),
                                MdmProcessParameterStandardEntity::getStandardNo, query.getStandardNo())
                        .like(StrUtil.isNotBlank(query.getStandardName()),
                                MdmProcessParameterStandardEntity::getStandardName, query.getStandardName())
                        .eq(query.getProcessRouteId() != null,
                                MdmProcessParameterStandardEntity::getProcessRouteId, query.getProcessRouteId())
                        .eq(StrUtil.isNotBlank(query.getRouteCode()),
                                MdmProcessParameterStandardEntity::getRouteCode, query.getRouteCode())
                        .eq(StrUtil.isNotBlank(query.getStatus()),
                                MdmProcessParameterStandardEntity::getStatus, query.getStatus())
                        .orderByDesc(MdmProcessParameterStandardEntity::getCreateTime)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_view')")
    public R<MdmProcessParameterStandardDTO.Detail> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping
    @SysLog("新增工艺参数标准草稿")
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_edit')")
    public R<Long> create(@Valid @RequestBody MdmProcessParameterStandardDTO.SaveCommand command) {
        return R.ok(service.createDraft(command));
    }

    @PutMapping
    @SysLog("修改工艺参数标准草稿")
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_edit')")
    public R<Boolean> update(@Valid @RequestBody MdmProcessParameterStandardDTO.SaveCommand command) {
        return R.ok(service.updateDraft(command));
    }

    @DeleteMapping("/{id}")
    @SysLog("删除工艺参数标准草稿")
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_edit')")
    public R<Boolean> delete(@PathVariable Long id) {
        return R.ok(service.deleteDraft(id));
    }

    @PostMapping("/{id}/copy-version")
    @SysLog("复制工艺参数标准新版本")
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_edit')")
    public R<Long> copyVersion(@PathVariable Long id) {
        return R.ok(service.copyVersion(id));
    }

    @PutMapping("/status")
    @SysLog("发布或退役工艺参数标准")
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_approve')")
    public R<Boolean> status(@Valid @RequestBody MdmProcessParameterStandardDTO.ActionCommand command) {
        String action = StrUtil.blankToDefault(command.getAction(), "").toUpperCase(Locale.ROOT);
        if (!List.of("RELEASE", "RETIRE").contains(action)) {
            throw new CheckedException("该入口只允许发布或退役工艺参数标准");
        }
        return R.ok(service.changeStatus(command));
    }

    @PostMapping("/internal/resolve")
    @Inner
    public R<MdmProcessParameterStandardDTO.FrozenCatalog> resolve(
            @Valid @RequestBody MdmProcessParameterStandardDTO.ResolveQuery query) {
        return R.ok(service.resolveReleased(query));
    }

    /**
     * 节点感知消费者专用，不以旧主工序接口暗中降级。
     */
    @PostMapping("/internal/resolve-nodes")
    @Inner
    public R<MdmProcessParameterStandardDTO.FrozenCatalog> resolveNodes(
            @Valid @RequestBody MdmProcessParameterStandardDTO.ResolveQuery query) {
        return R.ok(service.resolveNodes(query));
    }
}
