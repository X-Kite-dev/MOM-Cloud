package com.kitex.mom.mes.controller;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.entity.MesScrapMaterialEntity;
import com.kitex.mom.mes.service.MesScrapMaterialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 报废品管理
 *
 * @author system
 * @since 2025-01-27
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesScrapMaterial")
@Tag(description = "mesScrapMaterial", name = "报废品管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesScrapMaterialController {

    private final MesScrapMaterialService service;

    /**
     * 分页查询
     *
     * @param pageParam 分页参数
     * @param query     查询条件
     * @return 分页结果
     */

    @Operation(summary = "分页查询", description = "分页查询报废品管理信息")
    @GetMapping("/page")
//    @PreAuthorize("@pms.hasPermission('mesScrapMaterial:show')")
    public R<IPage<MesScrapMaterialEntity>> page(Page<MesScrapMaterialEntity> pageParam, MesScrapMaterialEntity query) {
        return R.ok(service.queryPage(pageParam, query));
    }

    /**
     * 通过id查询报废品管理
     *
     * @param id 主键id
     * @return 报废品管理信息
     */
    @Operation(summary = "通过id查询", description = "通过id查询报废品管理信息")
    @GetMapping("/{id}")
//    @PreAuthorize("@pms.hasPermission('mesScrapMaterial:show')")
    public R<MesScrapMaterialEntity> getById(@PathVariable("id") Long id) {
        return R.ok(service.getById(id));
    }

    /**
     * 新增报废品管理
     *
     * @param mesScrapMaterial 报废品管理信息
     * @return 操作结果
     */
    @Operation(summary = "新增报废品管理", description = "新增报废品管理")
    @SysLog("新增报废品管理")
    @PostMapping
//    @PreAuthorize("@pms.hasPermission('mesScrapMaterial:add')")
    public R<Void> save(@RequestBody MesScrapMaterialEntity mesScrapMaterial) {
        service.create(mesScrapMaterial);
        return R.ok();
    }

    /**
     * 修改报废品管理
     *
     * @param mesScrapMaterial 报废品管理信息
     * @return 操作结果
     */
    @Operation(summary = "修改报废品管理", description = "修改报废品管理")
    @SysLog("修改报废品管理")
    @PutMapping
//    @PreAuthorize("@pms.hasPermission('mesScrapMaterial:edit')")
    public R<Void> updateById(@RequestBody MesScrapMaterialEntity mesScrapMaterial) {
        service.edit(mesScrapMaterial);
        return R.ok();
    }

    /**
     * 通过id删除报废品管理
     *
     * @param ids id列表
     * @return 操作结果
     */
    @Operation(summary = "通过id删除", description = "通过id删除报废品管理")
    @SysLog("通过id删除报废品管理")
    @DeleteMapping
//    @PreAuthorize("@pms.hasPermission('mesScrapMaterial:del')")
    public R<Void> removeById(@RequestBody List<Long> ids) {
        service.deletePending(ids);
        return R.ok();
    }

    /**
     * 导出报废品管理
     *
     * @param query    查询条件
     * @param response 响应对象
     */
    @Operation(summary = "导出报废品管理", description = "导出报废品管理数据")
    @SysLog("导出报废品管理")
    @PostMapping("/export")
    @PreAuthorize("@pms.hasPermission('mesScrapMaterial:export')")
    public void export(@RequestBody MesScrapMaterialEntity query, HttpServletResponse response) {
        service.export(query, response);
    }

    /**
     * 导入报废品管理
     *
     * @param file 导入文件
     * @return 操作结果
     */
    @Operation(summary = "导入报废品管理", description = "导入报废品管理数据")
    @SysLog("导入报废品管理")
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mesScrapMaterial:import')")
    public R<String> importData(@RequestParam("file") MultipartFile file) {
        return R.ok(service.importData(file));
    }

    /**
     * 下载导入模板
     *
     * @param response 响应对象
     */
    @Operation(summary = "下载导入模板", description = "下载报废品管理导入模板")
    @GetMapping("/template")
    @PreAuthorize("@pms.hasPermission('mesScrapMaterial:template')")
    public void template(HttpServletResponse response) {
        service.template(response);
    }

    /**
     * 审核报废品
     *
     * @param id 主键id
     * @return 操作结果
     */
    @Operation(summary = "审核报废品", description = "审核报废品")
    @SysLog("审核报废品")
    @PostMapping("/audit/{id}")
    @PreAuthorize("@pms.hasPermission('mesScrapMaterial:audit')")
    public R<Void> audit(@PathVariable("id") Long id) {
        service.audit(id);
        return R.ok();
    }

    /**
     * 取消审核报废品
     *
     * @param id 主键id
     * @return 操作结果
     */
    @Operation(summary = "取消审核报废品", description = "取消审核报废品")
    @SysLog("取消审核报废品")
    @PostMapping("/cancelAudit/{id}")
    @PreAuthorize("@pms.hasPermission('mesScrapMaterial:audit')")
    public R<Void> cancelAudit(@PathVariable("id") Long id) {
        service.cancelAudit(id);
        return R.ok();
    }
}
