package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteAddDTO;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteQueryDTO;
import com.kitex.mom.wms.dto.app.WmsMaterialMoveSiteAppDTO;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import com.kitex.mom.wms.service.WmsMaterialMoveSiteService;
import com.kitex.mom.wms.service.impl.WmsMaterialMoveSiteAccess;
import com.kitex.mom.wms.service.impl.WmsMaterialMoveSiteQueryScope;
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
    private final WmsMaterialMoveSiteAccess access;

    /**
     * 分页查询
     *
     * @param page                分页对象
     * @param wmsMaterialMoveSite 库内移动
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    public R<Page<WmsMaterialMoveSiteEntity>> getWmsMaterialMoveSitePage(@ParameterObject SearchPage page, @ParameterObject WmsMaterialMoveSiteQueryDTO wmsMaterialMoveSite) {
        if (wmsMaterialMoveSite == null) {
            throw new CheckedException("库内移动查询条件不能为空");
        }
        access.requireView(wmsMaterialMoveSite.getWarehouseCode());
        return R.ok(wmsMaterialMoveSiteService.page(page.toMpPage(),
                WmsMaterialMoveSiteQueryScope.build(wmsMaterialMoveSite, null)));
    }

    /**
     * 通过id查询库内移动
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    public R<WmsMaterialMoveSiteEntity> getById(@PathVariable("id") Long id) {
        WmsMaterialMoveSiteEntity row = wmsMaterialMoveSiteService.getById(id);
        access.requireViewRow(row);
        return R.ok(row);
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
    public R<Boolean> save(@RequestBody WmsMaterialMoveSiteAddDTO wmsMaterialMoveSiteAddDTO) {
        if (wmsMaterialMoveSiteAddDTO == null) {
            throw new CheckedException("库内移动数据不能为空");
        }
        access.requireMove(wmsMaterialMoveSiteAddDTO.getWarehouseCode());
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
    public R<Boolean> appSave(@RequestBody @Valid WmsMaterialMoveSiteAppDTO wmsMaterialMoveSiteAddDTO) {
        if (wmsMaterialMoveSiteAddDTO == null) {
            throw new CheckedException("库内移动数据不能为空");
        }
        access.requireMove(wmsMaterialMoveSiteAddDTO.getWarehouseCode());
        return R.ok(wmsMaterialMoveSiteService.appMaterialMoveSiteSave(wmsMaterialMoveSiteAddDTO));
    }

    /**
     * 拒绝删除已生效的移库履历
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "移库履历禁止删除", description = "已发生库存移动的履历属于追溯证据")
    @SysLog("尝试删除移库履历")
    @DeleteMapping
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        throw new CheckedException("移库履历是库存追溯证据，不能删除；需要纠正库存请执行反向移库");
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
    public List<WmsMaterialMoveSiteExcelVO> export(@ParameterObject WmsMaterialMoveSiteQueryDTO wmsMaterialMoveSite,
            Long[] ids) {
        if (wmsMaterialMoveSite == null) {
            throw new CheckedException("库内移动查询条件不能为空");
        }
        access.requireExport(wmsMaterialMoveSite.getWarehouseCode());
        return wmsMaterialMoveSiteService.export(wmsMaterialMoveSite, ids);
    }
}
