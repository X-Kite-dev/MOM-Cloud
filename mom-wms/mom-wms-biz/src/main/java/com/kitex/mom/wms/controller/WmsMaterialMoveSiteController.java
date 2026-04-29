package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteAddDTO;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteQueryDTO;
import com.kitex.mom.wms.dto.app.WmsMaterialMoveSiteAppDTO;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import com.kitex.mom.wms.service.WmsMaterialMoveSiteService;
import com.kitex.mom.wms.vo.WmsMaterialMoveSiteExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 库内移动
 *
 * @author kitex
 * @date 2024-12-30 14:13:22
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsMaterialMoveSite")
@Tag(description = "wmsMaterialMoveSite", name = "库内移动管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsMaterialMoveSiteController {

    private final WmsMaterialMoveSiteService wmsMaterialMoveSiteService;

    /**
     * 分页查询
     *
     * @param page                分页对象
     * @param wmsMaterialMoveSite 库内移动
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
//    @PreAuthorize("@pms.hasPermission('wms_wmsMaterialMoveSite_view')")
    public R<Page<WmsMaterialMoveSiteEntity>> getWmsMaterialMoveSitePage(@ParameterObject SearchPage page, @ParameterObject WmsMaterialMoveSiteQueryDTO wmsMaterialMoveSite) {
        LambdaQueryWrapper<WmsMaterialMoveSiteEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsMaterialMoveSite.getMatCode()), WmsMaterialMoveSiteEntity::getMatCode, wmsMaterialMoveSite.getMatCode());
        wrapper.like(StrUtil.isNotBlank(wmsMaterialMoveSite.getMatName()), WmsMaterialMoveSiteEntity::getMatName, wmsMaterialMoveSite.getMatName());
        wrapper.like(StrUtil.isNotBlank(wmsMaterialMoveSite.getNewSiteCode()), WmsMaterialMoveSiteEntity::getNewSiteCode, wmsMaterialMoveSite.getNewSiteCode());
        wrapper.like(StrUtil.isNotBlank(wmsMaterialMoveSite.getNewSiteName()), WmsMaterialMoveSiteEntity::getNewSiteName, wmsMaterialMoveSite.getNewSiteName());
        wrapper.like(StrUtil.isNotBlank(wmsMaterialMoveSite.getOldSiteCode()), WmsMaterialMoveSiteEntity::getOldSiteCode, wmsMaterialMoveSite.getOldSiteCode());
        wrapper.like(StrUtil.isNotBlank(wmsMaterialMoveSite.getOldSiteName()), WmsMaterialMoveSiteEntity::getOldSiteName, wmsMaterialMoveSite.getOldSiteName());
        wrapper.eq(StrUtil.isNotBlank(wmsMaterialMoveSite.getWarehouseCode()), WmsMaterialMoveSiteEntity::getWarehouseCode, wmsMaterialMoveSite.getWarehouseCode());
        wrapper.orderByDesc(WmsMaterialMoveSiteEntity::getCreateTime);
        return R.ok(wmsMaterialMoveSiteService.page(page.toMpPage(), wrapper));
    }

    /**
     * 通过id查询库内移动
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
//    @PreAuthorize("@pms.hasPermission('wms_wmsMaterialMoveSite_view')")
    public R<WmsMaterialMoveSiteEntity> getById(@PathVariable("id") Long id) {
        return R.ok(wmsMaterialMoveSiteService.getById(id));
    }

    /**
     * 新增库内移动
     *
     * @param wmsMaterialMoveSiteAddDTO 库内移动
     * @return R
     */
    @Operation(summary = "新增库内移动", description = "新增库内移动")
    @SysLog("新增库内移动")
    @PostMapping
//    @PreAuthorize("@pms.hasPermission('wms_wmsMaterialMoveSite_add')")
    public R<Boolean> save(@RequestBody WmsMaterialMoveSiteAddDTO wmsMaterialMoveSiteAddDTO) {
        return R.ok(wmsMaterialMoveSiteService.materialMoveSiteSave(wmsMaterialMoveSiteAddDTO));
    }

    /**
     * app-库内移动
     *
     * @param wmsMaterialMoveSiteAddDTO 库内移动
     * @return R
     */
    @Operation(summary = "app-库内移动", description = "app-库内移动")
    @SysLog("app-库内移动")
    @PostMapping("/app")
//    @PreAuthorize("@pms.hasPermission('wms_wmsMaterialMoveSite_add')")
    public R<Boolean> appSave(@RequestBody @Valid WmsMaterialMoveSiteAppDTO wmsMaterialMoveSiteAddDTO) {
        return R.ok(wmsMaterialMoveSiteService.appMaterialMoveSiteSave(wmsMaterialMoveSiteAddDTO));
    }

    /**
     * 通过id删除库内移动
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除库内移动", description = "通过id删除库内移动")
    @SysLog("通过id删除库内移动")
    @DeleteMapping
//    @PreAuthorize("@pms.hasPermission('wms_wmsMaterialMoveSite_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(wmsMaterialMoveSiteService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param wmsMaterialMoveSite 查询条件
     * @param ids                 导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
//    @PreAuthorize("@pms.hasPermission('wms_wmsMaterialMoveSite_export')")
    public List<WmsMaterialMoveSiteExcelVO> export(WmsMaterialMoveSiteEntity wmsMaterialMoveSite, Long[] ids) {
        return wmsMaterialMoveSiteService.export(wmsMaterialMoveSite, ids);
    }
}