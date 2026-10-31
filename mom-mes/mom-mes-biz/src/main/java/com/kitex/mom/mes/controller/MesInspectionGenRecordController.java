package com.kitex.mom.mes.controller;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.service.MesInspectionGenRecordService;
import com.kitex.mom.mes.service.impl.MesInspectionRecordQuery;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import com.kitex.mom.mes.dto.MesInspectionRecordWorkbenchDTO;
import com.kitex.common.data.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 质检任务生成记录 管理
 *
 * @author kitex
 * @since 2024-02-06
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesInspectionGenRecord")
@Tag(name = "质检任务生成记录管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesInspectionGenRecordController {

    private final MesInspectionGenRecordService mesInspectionGenRecordService;
    private final MesInspectionRecordQuery workbenchQuery;
    private final MesActualFactoryAccessService factoryAccess;

    @GetMapping("/workbench")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:show')")
    public R<MesInspectionRecordWorkbenchDTO.PageView> workbench(
            MesInspectionRecordWorkbenchDTO.Query query) {
        return R.ok(workbenchQuery.page(query));
    }

    @GetMapping("/workbench/{id}")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:show')")
    public R<MesInspectionRecordWorkbenchDTO.Detail> workbenchDetail(@PathVariable Long id) {
        return R.ok(workbenchQuery.detail(id));
    }

    /**
     * 分页查询
     *
     * @param page  分页对象
     * @param query 质检任务生成记录
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:show')")
    public R<IPage<MesInspectionGenRecordEntity>> getMesInspectionGenRecordPage(Page<MesInspectionGenRecordEntity> page, MesInspectionGenRecordEntity query) {
        String factory = factoryAccess.require("read");
        QueryWrapper<MesInspectionGenRecordEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.eq("tenant_id", TenantContextHolder.getTenantId()).eq("factory_code", factory);
        return R.ok(mesInspectionGenRecordService.page(page, autoQueryWrapper));
    }

    /**
     * 通过id查询质检任务生成记录
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:show')")
    public R getById(@PathVariable("id") Long id) {
        String factory = factoryAccess.require("read");
        return R.ok(mesInspectionGenRecordService.getOne(new QueryWrapper<MesInspectionGenRecordEntity>()
                .eq("id", id).eq("tenant_id", TenantContextHolder.getTenantId()).eq("factory_code", factory)));
    }

}
