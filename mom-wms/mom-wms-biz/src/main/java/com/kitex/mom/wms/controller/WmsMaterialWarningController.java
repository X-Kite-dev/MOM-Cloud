package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import java.util.Objects;

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
    public R<Page<WmsMaterialWarningEntity>> getWmsMaterialWarningPage(@ParameterObject SearchPage page, @ParameterObject WmsMaterialWarningQueryDTO wmsMaterialWarning) {
        LambdaQueryWrapper<WmsMaterialWarningEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsMaterialWarning.getMatCode()), WmsMaterialWarningEntity::getMatCode, wmsMaterialWarning.getMatCode());
        wrapper.like(StrUtil.isNotBlank(wmsMaterialWarning.getMatName()), WmsMaterialWarningEntity::getMatName, wmsMaterialWarning.getMatName());
        wrapper.like(StrUtil.isNotBlank(wmsMaterialWarning.getMatBatch()), WmsMaterialWarningEntity::getMatBatch, wmsMaterialWarning.getMatBatch());
        wrapper.like(StrUtil.isNotBlank(wmsMaterialWarning.getWarehouseCode()), WmsMaterialWarningEntity::getWarehouseCode, wmsMaterialWarning.getWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsMaterialWarning.getWarehouseName()), WmsMaterialWarningEntity::getWarehouseName, wmsMaterialWarning.getWarehouseName());
        wrapper.like(StrUtil.isNotBlank(wmsMaterialWarning.getSiteCode()), WmsMaterialWarningEntity::getSiteCode, wmsMaterialWarning.getSiteCode());
        wrapper.like(StrUtil.isNotBlank(wmsMaterialWarning.getSiteName()), WmsMaterialWarningEntity::getSiteName, wmsMaterialWarning.getSiteName());
        wrapper.eq(Objects.nonNull(wmsMaterialWarning.getType()), WmsMaterialWarningEntity::getType, wmsMaterialWarning.getType());
        if (ArrayUtil.isNotEmpty(wmsMaterialWarning.getCreateTime())) {
            wrapper.between(WmsMaterialWarningEntity::getCreateTime, wmsMaterialWarning.getCreateTime()[0],
                    wmsMaterialWarning.getCreateTime()[1]);
        }
        return R.ok(wmsMaterialWarningService.page(page.toMpPage(), wrapper));
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
        return R.ok(wmsMaterialWarningService.getById(id));
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
        return R.ok(wmsMaterialWarningService.removeBatchByIds(CollUtil.toList(ids)));
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
    public List<WmsMaterialWarningExcelVO> export(WmsMaterialWarningEntity wmsMaterialWarning, Long[] ids) {
        return wmsMaterialWarningService.export(wmsMaterialWarning, ids);
    }
}