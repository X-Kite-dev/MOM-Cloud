package com.kitex.mom.mes.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.entity.MesWarehouseInventoryEntity;
import com.kitex.mom.mes.service.MesWarehouseInventoryService;
import com.kitex.mom.mes.vo.MesWarehouseInventoryExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 线边库物料盘点
 *
 * @author kitex
 * @date 2025-2-25 11:35:58
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehouseInventory")
@Tag(description = "MesWarehouseInventory", name = "线边库物料盘点")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWarehouseInventoryController {

    private final MesWarehouseInventoryService mesWarehouseInventoryService;

    /**
     * 分页查询
     *
     * @param page            分页对象
     * @param inventoryEntity 仓库盘点计划
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view')")
    public R<Page<MesWarehouseInventoryEntity>> getWmsInsideInventoryPlanPage(@ParameterObject SearchPage page, @ParameterObject MesWarehouseInventoryEntity inventoryEntity) {
        QueryWrapper<MesWarehouseInventoryEntity> wrapper = getWrapper(inventoryEntity);
        return R.ok(mesWarehouseInventoryService.page(page.toMpPage(), wrapper));
    }

    public QueryWrapper<MesWarehouseInventoryEntity> getWrapper(MesWarehouseInventoryEntity inventoryEntity) {
        QueryWrapper<MesWarehouseInventoryEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().eq(StrUtil.isNotBlank(inventoryEntity.getWarehouseCode()), MesWarehouseInventoryEntity::getWarehouseCode, inventoryEntity.getWarehouseCode());
        wrapper.lambda().like(StrUtil.isNotBlank(inventoryEntity.getCode()), MesWarehouseInventoryEntity::getCode, inventoryEntity.getCode());
        wrapper.lambda().eq(StrUtil.isNotBlank(inventoryEntity.getStatus()), MesWarehouseInventoryEntity::getStatus, inventoryEntity.getStatus());
        wrapper.lambda().eq(StrUtil.isNotBlank(inventoryEntity.getHandleState()), MesWarehouseInventoryEntity::getHandleState, inventoryEntity.getHandleState());
        wrapper.lambda().orderByAsc(MesWarehouseInventoryEntity::getStatus).orderByAsc(MesWarehouseInventoryEntity::getHandleState).orderByDesc(MesWarehouseInventoryEntity::getCreateTime);
        return wrapper;
    }


    @Operation(summary = "查询质检任务状态的数量", description = "查询各质检任务状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject MesWarehouseInventoryEntity inventoryEntity) {
        QueryWrapper<MesWarehouseInventoryEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().eq(StrUtil.isNotBlank(inventoryEntity.getWarehouseCode()), MesWarehouseInventoryEntity::getWarehouseCode, inventoryEntity.getWarehouseCode());
        wrapper.lambda().like(StrUtil.isNotBlank(inventoryEntity.getCode()), MesWarehouseInventoryEntity::getCode, inventoryEntity.getCode());
        wrapper.lambda().eq(StrUtil.isNotBlank(inventoryEntity.getHandleState()), MesWarehouseInventoryEntity::getHandleState, inventoryEntity.getHandleState());
        wrapper.lambda().orderByAsc(MesWarehouseInventoryEntity::getStatus).orderByAsc(MesWarehouseInventoryEntity::getHandleState).orderByDesc(MesWarehouseInventoryEntity::getCreateTime);
        return R.ok(mesWarehouseInventoryService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MesWarehouseInventoryEntity::getStatus, Collectors.counting())));
    }


//    /**
//     * app-分页查询
//     *
//     * @param page                   分页对象
//     * @param wmsInsideInventoryPlan 仓库盘点计划
//     * @return
//     */
//    @Operation(summary = "app-分页查询", description = "app-分页查询")
//    @GetMapping("/app/page")
////    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view')")
//    public R<Page<MesWarehouseInventoryEntity>> getWmsInsideInventoryPlanAppPage(@ParameterObject SearchPage page, @ParameterObject WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan) {
//        return R.ok(mesWarehouseInventoryService.appPage(page.toMpPage(), wmsInsideInventoryPlan));
//    }

    /**
     * 根据盘点编码获取详情
     *
     * @param code 盘点编码
     * @return R
     */
    @Operation(summary = "根据盘点编码获取详情", description = "根据盘点编码获取详情")
    @GetMapping("/details")
    public R<MesWarehouseInventoryEntity> details(String code) {
        return R.ok(mesWarehouseInventoryService
                .getOne(Wrappers.<MesWarehouseInventoryEntity>lambdaQuery().eq(MesWarehouseInventoryEntity::getCode, code)));
    }

    /**
     * 通过id查询仓库盘点计划
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view')")
    public R<MesWarehouseInventoryEntity> getById(@PathVariable("id") Long id) {
        return R.ok(mesWarehouseInventoryService.getDetails(id));
    }

    /**
     * 新增仓库盘点计划
     *
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return R
     */
    @Operation(summary = "新增仓库盘点计划", description = "新增仓库盘点计划")
    @SysLog("新增仓库盘点计划")
    @PostMapping("/save")
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add')")
    public R<Boolean> save(@RequestBody MesWarehouseInventoryEntity wmsInsideInventoryPlan) {
        return R.ok(mesWarehouseInventoryService.saveDeep(wmsInsideInventoryPlan));
    }

    /**
     * 盘点录入
     *
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return R
     */
    @Operation(summary = "盘点录入", description = "盘点录入")
    @SysLog("盘点录入")
    @PutMapping("/inventory")
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> inventory(@RequestBody MesWarehouseInventoryEntity wmsInsideInventoryPlan) {
        return R.ok(mesWarehouseInventoryService.inventory(wmsInsideInventoryPlan));
    }

//    /**
//     * app-盘点录入
//     *
//     * @param wmsInsideInventoryPlanDetail 仓库盘点计划详情
//     * @return R
//     */
//    @Operation(summary = "app-盘点录入", description = "app-盘点录入")
//    @SysLog("app-盘点录入")
//    @PutMapping("/app/inventory")
////    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_edit')")
//    public R<Boolean> appInventory(@RequestBody WmsInsideInventoryPlanDetailEntity wmsInsideInventoryPlanDetail) {
//        return R.ok(mesWarehouseInventoryService.appInventory(wmsInsideInventoryPlanDetail));
//    }

//    /**
//     * app-盘点录入完成
//     *
//     * @param id 仓库盘点计划id
//     * @return R
//     */
//    @Operation(summary = "app-盘点录入完成", description = "app-盘点录入完成")
//    @SysLog("app-盘点录入完成")
//    @PutMapping("/app/inventory/{id}")
////    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_edit')")
//    public R<Boolean> appInventorySuccess(@PathVariable("id") Long id) {
//        return R.ok(mesWarehouseInventoryService.appInventorySuccess(id));
//    }

    /**
     * 损益处理
     *
     * @param id 仓库盘点计划详情id
     * @return R
     */
    @Operation(summary = "损益处理", description = "损益处理")
    @SysLog("损益处理")
    @PutMapping("/detailComplete/{id}")
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> detailComplete(@PathVariable("id") Long id) {
        return R.ok(mesWarehouseInventoryService.detailComplete(id));
    }

    /**
     * 完成损益处理
     *
     * @param id 仓库盘点计划id
     * @return R
     */
    @Operation(summary = "完成损益处理", description = "完成损益处理")
    @SysLog("完成损益处理")
    @PutMapping("/complete/{id}")
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> complete(@PathVariable("id") Long id) {
        return R.ok(mesWarehouseInventoryService.complete(id));
    }

    /**
     * 通过id删除仓库盘点计划
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除仓库盘点计划", description = "通过id删除仓库盘点计划")
    @SysLog("通过id删除仓库盘点计划")
    @DeleteMapping
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mesWarehouseInventoryService.removeDeep(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param inventory 查询条件
     * @param ids       导出指定ID
     * @return excel 文件流
     */

    @ResponseExcel
    @GetMapping("/export")
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_export')" )
    public List<MesWarehouseInventoryExcelVO> export(MesWarehouseInventoryEntity inventory, Long[] ids) {
        QueryWrapper<MesWarehouseInventoryEntity> wrapper = getWrapper(inventory);
        return mesWarehouseInventoryService.export(wrapper.lambda(), ids);
    }
}