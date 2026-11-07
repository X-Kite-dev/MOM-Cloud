package com.kitex.mom.wms.controller;

import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.dto.WmsLineDeliveryCommand;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.service.bill.WmsOutBillApplicationService;
import com.kitex.mom.wms.service.impl.WmsLineDeliveryService;
import com.kitex.mom.wms.vo.WmsOutBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsOutBillExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import com.kitex.mom.wms.service.bill.WmsOutBillAccess.Family;

/**
 * 生产领料出库单，基础动作固定PRODUCE族并核真实菜单来源、工厂及原仓。
 *
 * @author kitex
 * @date 2024-12-25 14:23:18
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/produce/picking/outBill")
@Tag(description = "ProductionPickingBill", name = "生产领料出库单")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsProductionPickingOutBillController {

	private final WmsOutBillApplicationService documents;


	private final WmsLineDeliveryService lineDeliveryService;

	/**
	 * 分页查询生产领料出库单
	 *
	 * @param page       分页对象
	 * @param wmsOutBill 出库单
	 * @return
	 */
	@Operation(summary = "分页查询生产领料出库单", description = "分页查询生产领料出库单")
	@GetMapping("/page")

	public R getWmsOutBillPage(@ParameterObject SearchPage page,
	                           @ParameterObject WmsInBillDetailQueryDTO wmsOutBill) {
		return R.ok(documents.page(Family.PRODUCE, page.toMpPage(), wmsOutBill));
	}

	/**
	 * 分页查询明细
	 *
	 * @param page   分页对象
	 * @param params 出库单
	 * @return
	 */
	@Operation(summary = "分页查询明细", description = "分页查询明细")
	@GetMapping("/pageDetails")

	public R getWmsOutBillPageDetails(@ParameterObject SearchPage page,
	                                  @ParameterObject WmsInBillDetailQueryDTO params) {
		return R.ok(documents.pageDetails(Family.PRODUCE, page.toMpPage(), params));
	}

	/**
	 * 通过id查询出库单(含明细、最大库存)
	 *
	 * @param id id
	 * @return R
	 */
	@Operation(summary = "通过id查询出库单(含明细、最大库存)", description = "通过id查询出库单(含明细、最大库存)")
	@GetMapping("/{id}")

	public R getById(@PathVariable("id") Long id) {
		return R.ok(documents.view(Family.PRODUCE, id));
	}

	/**
	 * 通过id查询出库单(含明细)
	 *
	 * @param id id
	 * @return R
	 */
	@Operation(summary = "通过id查询出库单(含明细)", description = "通过id查询出库单(含明细)")
	@GetMapping("/getDeepById/{id}")
	public R<WmsOutBillEntity> getDeepById(@PathVariable("id") Long id) {
		return R.ok(documents.deepView(Family.PRODUCE, id));
	}

	/**
	 * 新增出库单
	 *
	 * @param wmsOutBill 出库单
	 * @return R
	 */
	@Operation(summary = "新增出库单", description = "新增出库单")
	@SysLog("新增出库单")
	@PostMapping

	public R save(@RequestBody WmsOutBillEntity wmsOutBill) {
		return R.ok(documents.create(Family.PRODUCE, wmsOutBill, false));
	}

	/**
	 * 新增出库单并直接审核通过
	 *
	 * @param wmsOutBill 出库单
	 * @return R
	 */
	@Operation(summary = "新增出库单并直接审核通过", description = "新增出库单并直接审核通过")
	@SysLog("新增出库单并直接审核通过")
	@PostMapping("/addAndPass")

	public R addAndPass(@RequestBody WmsOutBillEntity wmsOutBill) {
		return R.ok(documents.create(Family.PRODUCE, wmsOutBill, true));
	}

	/**
	 * 修改出库单
	 *
	 * @param wmsOutBill 出库单
	 * @return R
	 */
	@Operation(summary = "修改出库单", description = "修改出库单")
	@SysLog("修改出库单")
	@PutMapping

	public R updateById(@RequestBody WmsOutBillEntity wmsOutBill) {
		return R.ok(documents.update(Family.PRODUCE, wmsOutBill));
	}

	/**
	 * 通过id删除出库单
	 *
	 * @param ids id列表
	 * @return R
	 */
	@Operation(summary = "通过id删除出库单", description = "通过id删除出库单")
	@SysLog("通过id删除出库单")
	@DeleteMapping

	public R removeById(@RequestBody String[] ids) {
		return R.ok(documents.remove(Family.PRODUCE, ids));
	}

	/**
	 * 提交出库单
	 *
	 * @param id id
	 * @return R
	 */
	@Operation(summary = "提交出库单", description = "提交出库单")
	@GetMapping("/commitBill")

	public R commitBill(Long id) {
		return R.ok(documents.commit(Family.PRODUCE, id));
	}

	/**
	 * 审核通过出库单
	 *
	 * @param id id
	 * @return R
	 */
	@Operation(summary = "审核通过出库单", description = "审核通过出库单")
	@GetMapping("/passBill")

	public R passBill(Long id) {
		return R.ok(documents.pass(Family.PRODUCE, id));
	}

	/**
	 * 审核驳回出库单
	 *
	 * @param params 出库单id、驳回原因
	 * @return R
	 */
	@Operation(summary = "审核驳回出库单", description = "审核驳回出库单")
	@PostMapping("/rejectBill")

	public R rejectBill(@RequestBody WmsOutBillEntity params) {
		return R.ok(documents.reject(Family.PRODUCE, params));
	}

	@GetMapping("/{outBillId}/line-delivery")
	@PreAuthorize("@pms.hasPermission('wms_lineDelivery_view')")
	public R<?> lineDelivery(@PathVariable Long outBillId) {
		return R.ok(lineDeliveryService.viewByOutBill(outBillId));
	}

	@PostMapping("/line-delivery/{id}/dispatch")
	@SysLog("线边配送派单")
	@PreAuthorize("@pms.hasPermission('wms_lineDelivery_dispatch')")
	public R<?> dispatchLineDelivery(@PathVariable Long id,
	                                 @RequestBody WmsLineDeliveryCommand.Dispatch command) {
		return R.ok(lineDeliveryService.dispatch(id, command));
	}

	@PostMapping("/line-delivery/{id}/redispatch")
	@SysLog("线边配送未启运改派")
	@PreAuthorize("@pms.hasPermission('wms_lineDelivery_redispatch')")
	public R<?> redispatchLineDelivery(@PathVariable Long id,
	                                   @RequestBody WmsLineDeliveryCommand.Dispatch command) {
		return R.ok(lineDeliveryService.redispatch(id, command));
	}

	@PostMapping("/line-delivery/{id}/accept")
	@SysLog("线边配送接单")
	@PreAuthorize("@pms.hasPermission('wms_lineDelivery_accept')")
	public R<?> acceptLineDelivery(@PathVariable Long id, @RequestBody WmsLineDeliveryCommand.Accept command) {
		return R.ok(lineDeliveryService.accept(id, command));
	}

	@PostMapping("/line-delivery/{id}/transit")
	@SysLog("线边配送启运")
	@PreAuthorize("@pms.hasPermission('wms_lineDelivery_transit')")
	public R<?> transitLineDelivery(@PathVariable Long id,
	                                @RequestBody WmsLineDeliveryCommand.Transit command) {
		return R.ok(lineDeliveryService.transit(id, command));
	}

	@PostMapping("/line-delivery/{id}/arrive")
	@SysLog("线边配送到达确认")
	@PreAuthorize("@pms.hasPermission('wms_lineDelivery_arrive')")
	public R<?> arriveLineDelivery(@PathVariable Long id, @RequestBody WmsLineDeliveryCommand.Arrival command) {
		return R.ok(lineDeliveryService.arrive(id, command));
	}

	@PostMapping("/line-delivery/{id}/resolve")
	@SysLog("线边配送差异核销")
	@PreAuthorize("@pms.hasPermission('wms_lineDelivery_resolve')")
	public R<?> resolveLineDelivery(@PathVariable Long id,
	                                @RequestBody WmsLineDeliveryCommand.Resolve command) {
		return R.ok(lineDeliveryService.resolve(id, command));
	}

	/**
	 * 导出主表 excel 表格
	 *
	 * @param wmsOutNotice 查询条件
	 * @param ids          导出指定ID
	 * @return excel 文件流
	 */
	@ResponseExcel
	@GetMapping("/exportMain")

	public List<WmsOutBillExcelVO> exportMain(WmsInBillDetailQueryDTO wmsOutNotice, Long[] ids) {
		return documents.exportMain(Family.PRODUCE, wmsOutNotice, ids);
	}

	/**
	 * 导出明细 excel 表格
	 *
	 * @param params 查询条件
	 * @param ids    导出指定ID
	 * @return excel 文件流
	 */
	@ResponseExcel
	@GetMapping("/exportDetails")

	public List<WmsOutBillDetailExcelVO> exportDetails(WmsInBillDetailQueryDTO params, Long[] ids) {
		return documents.exportDetails(Family.PRODUCE, params, ids);
	}


	/**
	 * 按当前工厂、真实仓库及固定业务族统计状态，不接受外部授权族参数。
	 */
	@GetMapping("/getCount")
	public R getCount(@ParameterObject WmsInBillDetailQueryDTO query) {
		return R.ok(documents.count(Family.PRODUCE, query));
	}

	/**
	 * 当前入口的八项角色动作能力；不表示库存、质量、配置或工厂写授权已经放行。
	 */
	@PostMapping("/permissions")
	public R<Map<String, List<String>>> permissions(@RequestBody List<String> warehouseCodes) {
		return R.ok(documents.capabilities(Family.PRODUCE, warehouseCodes));
	}
}
