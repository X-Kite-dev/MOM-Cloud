package com.kitex.mom.wms.controller;

import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.dto.WmsSalesShipmentCorrectionCommand;
import com.kitex.mom.wms.entity.WmsOmsShipmentCorrectionOutboxEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.service.bill.WmsOutBillApplicationService;
import com.kitex.mom.wms.entity.WmsSalesShipmentCorrectionDetailEntity;
import com.kitex.mom.wms.entity.WmsSalesShipmentCorrectionEntity;
import com.kitex.mom.wms.service.impl.WmsOmsShipmentCorrectionOutboxService;
import com.kitex.mom.wms.service.impl.WmsSalesShipmentCorrectionService;
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
 * 销售出库单，基础动作固定SALES族并核真实菜单来源、工厂及原仓。
 *
 * @author kitex
 * @date 2024-12-16 13:47:16
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/sales/outBill")
@Tag(description = "wmsSalesOutBill", name = "销售出库单")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsSalesOutBillController {

	private final WmsOutBillApplicationService documents;

	private final WmsSalesShipmentCorrectionService shipmentCorrectionService;
	private final WmsOmsShipmentCorrectionOutboxService shipmentCorrectionOutboxService;


	@GetMapping("/correction/page")
	@PreAuthorize("@pms.hasPermission('wms_salesShipmentCorrection_view')")
	public R correctionPage(@ParameterObject SearchPage page,
	                        @ParameterObject WmsSalesShipmentCorrectionEntity query) {
		return R.ok(shipmentCorrectionService.page(page.toMpPage(), query));
	}

	@GetMapping("/correction/{id}/details")
	@PreAuthorize("@pms.hasPermission('wms_salesShipmentCorrection_view')")
	public R<List<WmsSalesShipmentCorrectionDetailEntity>> correctionDetails(@PathVariable Long id) {
		return R.ok(shipmentCorrectionService.details(id));
	}

	@GetMapping("/correction/byBill/{outBillId}")
	@PreAuthorize("@pms.hasPermission('wms_salesShipmentCorrection_view')")
	public R<WmsSalesShipmentCorrectionEntity> correctionByBill(@PathVariable Long outBillId) {
		return R.ok(shipmentCorrectionService.findByBill(outBillId));
	}

	@PostMapping("/correction/apply")
	@PreAuthorize("@pms.hasPermission('wms_salesShipmentCorrection_apply')")
	@SysLog("申请销售发运记账纠错")
	public R<WmsSalesShipmentCorrectionEntity> applyCorrection(
			@RequestBody WmsSalesShipmentCorrectionCommand command) {
		return R.ok(shipmentCorrectionService.apply(command));
	}

	@PostMapping("/correction/{id}/resubmit")
	@PreAuthorize("@pms.hasPermission('wms_salesShipmentCorrection_apply')")
	@SysLog("重新提交销售发运记账纠错")
	public R<WmsSalesShipmentCorrectionEntity> resubmitCorrection(@PathVariable Long id,
	                                                              @RequestBody WmsSalesShipmentCorrectionCommand command) {
		return R.ok(shipmentCorrectionService.resubmit(id, command));
	}

	@PostMapping("/correction/{id}/approve")
	@PreAuthorize("@pms.hasPermission('wms_salesShipmentCorrection_review')")
	@SysLog("复核并执行销售发运记账纠错")
	public R<WmsSalesShipmentCorrectionEntity> approveCorrection(@PathVariable Long id) {
		return R.ok(shipmentCorrectionService.approve(id));
	}

	@PostMapping("/correction/{id}/reject")
	@PreAuthorize("@pms.hasPermission('wms_salesShipmentCorrection_review')")
	@SysLog("驳回销售发运记账纠错")
	public R<WmsSalesShipmentCorrectionEntity> rejectCorrection(@PathVariable Long id,
	                                                            @RequestBody WmsSalesShipmentCorrectionCommand command) {
		return R.ok(shipmentCorrectionService.reject(id, command));
	}

	@GetMapping("/correction/{id}/events")
	@PreAuthorize("@pms.hasPermission('wms_salesShipmentCorrection_view')")
	public R<List<WmsOmsShipmentCorrectionOutboxEntity>> correctionEvents(@PathVariable Long id) {
		return R.ok(shipmentCorrectionOutboxService.listByCorrection(id));
	}

	@PostMapping("/correction/event/{id}/retry")
	@PreAuthorize("@pms.hasPermission('wms_salesShipmentCorrection_event_retry')")
	@SysLog("重试销售发运纠错OMS事件")
	public R<WmsOmsShipmentCorrectionOutboxEntity> retryCorrectionEvent(@PathVariable Long id) {
		return R.ok(shipmentCorrectionOutboxService.retry(id));
	}

	@PostMapping("/correction/event/{id}/reconcile")
	@PreAuthorize("@pms.hasPermission('wms_salesShipmentCorrection_event_view')")
	public R<WmsOmsShipmentCorrectionOutboxEntity> reconcileCorrectionEvent(@PathVariable Long id) {
		return R.ok(shipmentCorrectionOutboxService.reconcile(id));
	}

	/**
	 * 分页查询
	 *
	 * @param page       分页对象
	 * @param wmsOutBill 出库单
	 * @return
	 */
	@Operation(summary = "分页查询", description = "分页查询")
	@GetMapping("/page")

	public R getWmsOutBillPage(@ParameterObject SearchPage page,
	                           @ParameterObject WmsInBillDetailQueryDTO wmsOutBill) {
		return R.ok(documents.page(Family.SALES, page.toMpPage(), wmsOutBill));
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
		return R.ok(documents.pageDetails(Family.SALES, page.toMpPage(), params));
	}


	/**
	 * 通过id查询出库单
	 *
	 * @param id id
	 * @return R
	 */
	@Operation(summary = "通过id查询", description = "通过id查询")
	@GetMapping("/{id}")

	public R getById(@PathVariable("id") Long id) {
		return R.ok(documents.view(Family.SALES, id));
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
		return R.ok(documents.create(Family.SALES, wmsOutBill, false));
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
		return R.ok(documents.create(Family.SALES, wmsOutBill, true));
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
		return R.ok(documents.update(Family.SALES, wmsOutBill));
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
		return R.ok(documents.remove(Family.SALES, ids));
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
		return R.ok(documents.commit(Family.SALES, id));
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
		return R.ok(documents.pass(Family.SALES, id));
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
		return R.ok(documents.reject(Family.SALES, params));
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
		return documents.exportMain(Family.SALES, wmsOutNotice, ids);
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
		return documents.exportDetails(Family.SALES, params, ids);
	}


	/**
	 * 按当前工厂、真实仓库及固定业务族统计状态，不接受外部授权族参数。
	 */
	@GetMapping("/getCount")
	public R getCount(@ParameterObject WmsInBillDetailQueryDTO query) {
		return R.ok(documents.count(Family.SALES, query));
	}

	/**
	 * 当前入口的八项角色动作能力；不表示库存、质量、配置或工厂写授权已经放行。
	 */
	@PostMapping("/permissions")
	public R<Map<String, List<String>>> permissions(@RequestBody List<String> warehouseCodes) {
		return R.ok(documents.capabilities(Family.SALES, warehouseCodes));
	}
}
