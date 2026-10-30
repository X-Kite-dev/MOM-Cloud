package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsMaterialWarningQueryDTO;
import com.kitex.mom.wms.entity.WmsMaterialWarningEntity;
import com.kitex.mom.wms.service.WmsMaterialWarningService;
import com.kitex.mom.wms.vo.WmsMaterialWarningExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 物料预警表
 *
 * @author kitex
 * @date 2024-12-23 14:58:53
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsMaterialWarning")
@Tag(description = "wmsMaterialWarning", name = "物料预警表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsMaterialWarningController {

    private final WmsMaterialWarningService wmsMaterialWarningService;

    /**
     * 分页查询
     *
     * @param page               分页对象
     * @param wmsMaterialWarning 物料预警表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wms_wmsMaterialWarning_view')")
    public R<Page<WmsMaterialWarningEntity>> getWmsMaterialWarningPage(@ParameterObject SearchPage page,
                                                                       @ParameterObject WmsMaterialWarningQueryDTO wmsMaterialWarning) {
        return R.ok(
                wmsMaterialWarningService.page(page.toMpPage(), wmsMaterialWarningService.query(wmsMaterialWarning)));
    }

    /**
     * 通过id查询物料预警表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsMaterialWarning_view')")
    public R<WmsMaterialWarningEntity> getById(@PathVariable("id") Long id) {
        return R.ok(wmsMaterialWarningService.getScopedById(id));
    }

    /**
     * 通过id删除物料预警表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除物料预警表", description = "通过id删除物料预警表")
    @SysLog("通过id删除物料预警表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsMaterialWarning_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        throw new com.kitex.common.core.exception.CheckedException("物料预警是业务追溯记录，不支持删除");
    }

    /**
     * 导出excel 表格
     *
     * @param wmsMaterialWarning 查询条件
     * @param ids                导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('wms_wmsMaterialWarning_export')")
    public List<WmsMaterialWarningExcelVO> export(WmsMaterialWarningQueryDTO wmsMaterialWarning, Long[] ids) {
        return wmsMaterialWarningService.export(wmsMaterialWarning, ids);
    }
}
