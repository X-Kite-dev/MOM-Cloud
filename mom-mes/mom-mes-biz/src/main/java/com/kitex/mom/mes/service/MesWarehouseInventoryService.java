package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.mom.mes.entity.MesWarehouseInventoryEntity;
import com.kitex.mom.mes.vo.MesWarehouseInventoryExcelVO;

import java.util.List;

public interface MesWarehouseInventoryService extends MPJDeepService<MesWarehouseInventoryEntity> {

    /**
     * 新增仓库盘点计划
     *
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return R
     */
    Boolean saveDeep(MesWarehouseInventoryEntity wmsInsideInventoryPlan);

    /**
     * 盘点录入
     *
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return R
     */
    Boolean inventory(MesWarehouseInventoryEntity wmsInsideInventoryPlan);

    /**
     * 通过id删除仓库盘点计划
     *
     * @param ids id列表
     * @return R
     */
    Boolean removeDeep(Long[] ids);

    /**
     * 通过id查询仓库盘点计划
     *
     * @param id id
     * @return R
     */
    MesWarehouseInventoryEntity getDetails(Long id);

    /**
     * 损益处理
     *
     * @param id 仓库盘点计划id
     * @return R
     */
    Boolean complete(Long id);

    /**
     * app-盘点录入
     *
     * @param wmsInsideInventoryPlanDetail 仓库盘点计划详情
     * @return R
     */
//    Boolean appInventory(WmsInsideInventoryPlanDetailEntity wmsInsideInventoryPlanDetail);

    /**
     * app-盘点录入完成
     *
     * @param id 仓库盘点计划id
     * @return R
     */
//    Boolean appInventorySuccess(Long id);

    /**
     * 分页查询
     *
     * @param page                   分页对象
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return
     */
//    Page<MesWarehouseInventoryEntity> appPage(Page page, WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan);

    /**
     * 损益处理
     *
     * @param id 仓库盘点计划详情id
     * @return R
     */
    Boolean detailComplete(Long id);

    /**
     * 导出excel 表格
     *
     * @param inventoryEntity 查询条件
     * @param ids             导出指定ID
     * @return excel 文件流
     */
    List<MesWarehouseInventoryExcelVO> export(LambdaQueryWrapper<MesWarehouseInventoryEntity> inventoryEntity, Long[] ids);
}