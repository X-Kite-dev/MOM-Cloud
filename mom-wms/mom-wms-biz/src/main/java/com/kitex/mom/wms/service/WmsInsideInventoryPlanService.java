package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.mom.wms.dto.WmsInsideInventoryPlanQueryDTO;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.vo.WmsInsideInventoryPlanExcelVO;

import java.util.List;

public interface WmsInsideInventoryPlanService extends MPJDeepService<WmsInsideInventoryPlanEntity> {

	/**
	 * 当前数据库是否已启用实盘期间库存冻结守卫。
	 */
	Boolean countFreezeAvailable();

	/**
	 * 新增仓库盘点计划
	 *
	 * @param wmsInsideInventoryPlan 仓库盘点计划
	 * @return R
	 */
	Boolean saveDeep(WmsInsideInventoryPlanEntity wmsInsideInventoryPlan);

	Boolean saveDeepMobile(WmsInsideInventoryPlanEntity plan);

	/**
	 * 盘点录入
	 *
	 * @param wmsInsideInventoryPlan 仓库盘点计划
	 * @return R
	 */
	Boolean inventory(WmsInsideInventoryPlanEntity wmsInsideInventoryPlan);

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
	WmsInsideInventoryPlanEntity getDetails(Long id);

	/**
	 * 损益处理
	 *
	 * @param id 仓库盘点计划id
	 * @return R
	 */
	Boolean complete(Long id);

	Boolean completeMobile(Long id);


	Boolean completeDetails(Long id);


	/**
	 * app-盘点录入
	 *
	 * @param wmsInsideInventoryPlanDetail 仓库盘点计划详情
	 * @return R
	 */
	Boolean appInventory(WmsInsideInventoryPlanDetailEntity wmsInsideInventoryPlanDetail);

	/**
	 * app-盘点录入完成
	 *
	 * @param id 仓库盘点计划id
	 * @return R
	 */
	Boolean appInventorySuccess(Long id);

	/**
	 * 分页查询
	 *
	 * @param page                   分页对象
	 * @param wmsInsideInventoryPlan 仓库盘点计划
	 * @return
	 */
	Page<WmsInsideInventoryPlanEntity> appPage(Page page, WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan);

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
	 * @param wmsInsideInventoryPlan 查询条件
	 * @param ids                    导出指定ID
	 * @return excel 文件流
	 */
	List<WmsInsideInventoryPlanExcelVO> export(WmsInsideInventoryPlanEntity wmsInsideInventoryPlan, Long[] ids);

}
