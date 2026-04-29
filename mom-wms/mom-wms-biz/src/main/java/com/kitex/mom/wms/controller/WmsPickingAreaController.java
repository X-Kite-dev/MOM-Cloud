package com.kitex.mom.wms.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.wms.entity.WmsPickingAreaEntity;
import com.kitex.mom.wms.service.WmsPickingAreaService;
import com.kitex.mom.wms.vo.WmsPickingAreaExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 拣货区
 *
 * @author kitex
 * @date 2025-01-21 15:38:39
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsPickingArea")
@Tag(description = "wmsPickingArea", name = "拣货区管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsPickingAreaController {

    private final WmsPickingAreaService wmsPickingAreaService;

    /**
     * 分页查询
     *
     * @param page           分页对象
     * @param wmsPickingArea 拣货区
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    public R getWmsPickingAreaPage(@ParameterObject SearchPage page, @ParameterObject WmsPickingAreaEntity wmsPickingArea) {
        LambdaQueryWrapper<WmsPickingAreaEntity> wrapper = getWrapper(wmsPickingArea);
        return R.ok(wmsPickingAreaService.page(page.toMpPage(), wrapper));
    }

    public LambdaQueryWrapper<WmsPickingAreaEntity> getWrapper(WmsPickingAreaEntity wmsPickingArea) {
        LambdaQueryWrapper<WmsPickingAreaEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StrUtil.isNotBlank(wmsPickingArea.getWarehouseCode()), WmsPickingAreaEntity::getWarehouseCode, wmsPickingArea.getWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsPickingArea.getMatCode()), WmsPickingAreaEntity::getMatCode, wmsPickingArea.getMatCode());
        wrapper.like(StrUtil.isNotBlank(wmsPickingArea.getMatName()), WmsPickingAreaEntity::getMatName, wmsPickingArea.getMatName());
        return wrapper;
    }

    /**
     * 导出excel 表格
     *
     * @param wmsPickingArea 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    public List<WmsPickingAreaExcelVO> exportExcel(WmsPickingAreaEntity wmsPickingArea) {
        LambdaQueryWrapper<WmsPickingAreaEntity> wrapper = getWrapper(wmsPickingArea);
        return wmsPickingAreaService.exportExcel(wrapper);
    }

}