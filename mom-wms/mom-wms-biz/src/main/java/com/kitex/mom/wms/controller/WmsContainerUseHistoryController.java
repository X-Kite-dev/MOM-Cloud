package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.entity.WmsContainerUseHistoryEntity;
import com.kitex.mom.wms.service.WmsContainerUseHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 容器占用历史
 *
 * @author kitex
 * @date 2025-01-02 15:06:10
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsContainerUseHistory")
@Tag(description = "wmsContainerUseHistory", name = "容器占用历史管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsContainerUseHistoryController {

    private final WmsContainerUseHistoryService wmsContainerUseHistoryService;

    /**
     * 分页查询
     *
     * @param page   分页对象
     * @param params 容器占用历史
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerUseHistory_view')")
    public R getWmsContainerUseHistoryPage(@ParameterObject SearchPage page, @ParameterObject WmsContainerUseHistoryEntity params) {
        return R.ok(wmsContainerUseHistoryService.pageHistory(page.toMpPage(), params));
    }


    /**
     * 通过条件查询容器占用历史
     *
     * @param wmsContainerUseHistory 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerUseHistory_view')")
    public R getDetails(@ParameterObject WmsContainerUseHistoryEntity wmsContainerUseHistory) {
        return R.ok(wmsContainerUseHistoryService.list(Wrappers.query(wmsContainerUseHistory)));
    }

    /**
     * 新增容器占用历史
     *
     * @param wmsContainerUseHistory 容器占用历史
     * @return R
     */
    @Operation(summary = "新增容器占用历史", description = "新增容器占用历史")
    @SysLog("新增容器占用历史")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerUseHistory_add')")
    public R save(@RequestBody WmsContainerUseHistoryEntity wmsContainerUseHistory) {
        return R.ok(wmsContainerUseHistoryService.save(wmsContainerUseHistory));
    }

    /**
     * 修改容器占用历史
     *
     * @param wmsContainerUseHistory 容器占用历史
     * @return R
     */
    @Operation(summary = "修改容器占用历史", description = "修改容器占用历史")
    @SysLog("修改容器占用历史")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerUseHistory_edit')")
    public R updateById(@RequestBody WmsContainerUseHistoryEntity wmsContainerUseHistory) {
        return R.ok(wmsContainerUseHistoryService.updateById(wmsContainerUseHistory));
    }

    /**
     * 通过id删除容器占用历史
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除容器占用历史", description = "通过id删除容器占用历史")
    @SysLog("通过id删除容器占用历史")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerUseHistory_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(wmsContainerUseHistoryService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param wmsContainerUseHistory 查询条件
     * @param ids                    导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerUseHistory_export')")
    public List<WmsContainerUseHistoryEntity> exportExcel(WmsContainerUseHistoryEntity wmsContainerUseHistory, Long[] ids) {
        return wmsContainerUseHistoryService.list(Wrappers.lambdaQuery(wmsContainerUseHistory).in(ArrayUtil.isNotEmpty(ids), WmsContainerUseHistoryEntity::getId, ids));
    }

    /**
     * 导入excel 表
     *
     * @param wmsContainerUseHistoryList 对象实体列表
     * @param bindingResult              错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('wms_wmsContainerUseHistory_export')")
    public R importExcel(@RequestExcel List<WmsContainerUseHistoryEntity> wmsContainerUseHistoryList, BindingResult bindingResult) {
        return R.ok(wmsContainerUseHistoryService.saveBatch(wmsContainerUseHistoryList));
    }
}