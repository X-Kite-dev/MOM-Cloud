package com.kitex.mom.wms.controller;

import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.service.bill.WmsInBillAccess.Family;
import com.kitex.mom.wms.service.bill.WmsInBillApplicationService;
import com.kitex.mom.wms.vo.WmsInBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsInBillExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 采购入库单的固定Web入口；所有单据动作由办理门面核实际组织、原仓及动作权限。
 * 原质量、数量、状态及跨模块后续处理继续沿实际类型处理器执行。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsPurchaseInBill")
@Tag(description = "wmsPurchaseInBill", name = "采购入库单管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsPurchaseInBillController {

	private final WmsInBillApplicationService documents;

	/**
	 * 查询当前工厂及实际授权仓的入库单，固定业务族由本入口决定。
	 *
	 * @param page      分页参数
	 * @param wmsInBill 原通知、类型、状态、仓库和创建时间等筛选条件
	 * @return 授权范围内的分页；无查看权限时返回零行
	 */
	@Operation(summary = "分页查询", description = "分页查询")
	@GetMapping("/page")
	public R getWmsInBillPage(@ParameterObject SearchPage page,
	                          @ParameterObject WmsInBillDetailQueryDTO wmsInBill) {
		return R.ok(documents.page(Family.PURCHASE, page.toMpPage(), wmsInBill));
	}

	/**
	 * 查询入库明细；实际组织、固定业务族和授权仓范围由办理门面覆盖。
	 */
	@Operation(summary = "分页查询明细", description = "分页查询明细")
	@GetMapping("/pageDetails")
	public R getWmsInBillPageDetails(@ParameterObject SearchPage page,
	                                 @ParameterObject WmsInBillDetailQueryDTO params) {
		return R.ok(documents.pageDetails(Family.PURCHASE, page.toMpPage(), params));
	}

	/**
	 * 先核实际原单和原仓查看权限，再按存储类型读取详情。
	 */
	@Operation(summary = "通过id查询", description = "通过id查询")
	@GetMapping("/{id}")
	public R getById(@PathVariable("id") Long id) {
		return R.ok(documents.view(Family.PURCHASE, id));
	}

	/**
	 * 新增入库单；先核目标仓新增权限及关联通知真实归属，保留原类型来源校验。
	 */
	@Operation(summary = "新增入库单", description = "新增入库单")
	@SysLog("新增入库单")
	@PostMapping
	public R save(@RequestBody WmsInBillEntity wmsInBill) {
		return R.ok(documents.create(Family.PURCHASE, wmsInBill, false));
	}

	/**
	 * 新增并直接审核须有目标仓独立直接审核权限，原质量与实收规则继续生效。
	 */
	@Operation(summary = "新增入库单并直接审核通过", description = "新增入库单并直接审核通过")
	@SysLog("新增入库单并直接审核通过")
	@PostMapping("/addAndPass")
	public R addAndPass(@RequestBody WmsInBillEntity wmsInBill) {
		return R.ok(documents.create(Family.PURCHASE, wmsInBill, true));
	}

	/**
	 * 按真实原单类型核原仓与目标仓编辑权限，保留原来源通知和状态约束。
	 */
	@Operation(summary = "修改入库单", description = "修改入库单")
	@SysLog("修改入库单")
	@PutMapping
	public R updateById(@RequestBody WmsInBillEntity wmsInBill) {
		return R.ok(documents.update(Family.PURCHASE, wmsInBill));
	}

	/**
	 * 全批原单先核删除权限再按实际类型办理，任一原件无权或失败时整体回滚。
	 */
	@Operation(summary = "通过id删除入库单", description = "通过id删除入库单")
	@SysLog("通过id删除入库单")
	@DeleteMapping
	public R removeById(@RequestBody String[] ids) {
		return R.ok(documents.remove(Family.PURCHASE, ids));
	}

	/**
	 * 核实际原仓提交权限后提交原单，原状态与来源检查继续生效。
	 */
	@Operation(summary = "提交入库单", description = "提交入库单")
	@GetMapping("/commitBill")
	public R commitBill(Long id) {
		return R.ok(documents.commit(Family.PURCHASE, id));
	}

	/**
	 * 审核原单按其真实类型派发，保留采购回写及生产退料回执等业务后续处理。
	 */
	@Operation(summary = "审核通过入库单", description = "审核通过入库单")
	@GetMapping("/passBill")
	public R passBill(Long id) {
		return R.ok(documents.pass(Family.PURCHASE, id));
	}

	/**
	 * 沿实际原单审核权限驳回，只以正文ID和原驳回原因办理。
	 */
	@Operation(summary = "审核驳回入库单", description = "审核驳回入库单")
	@PostMapping("/rejectBill")
	public R rejectBill(@RequestBody WmsInBillEntity params) {
		return R.ok(documents.reject(Family.PURCHASE, params));
	}

	/**
	 * 指定主单整批核原仓导出权限；未选择时沿当前筛选及实际授权范围导出。
	 */
	@ResponseExcel
	@GetMapping("/exportMain")
	public List<WmsInBillExcelVO> exportMain(WmsInBillDetailQueryDTO wmsInNotice, Long[] ids) {
		return documents.exportMain(Family.PURCHASE, wmsInNotice, ids);
	}

	/**
	 * 明细ID须关联同组织实际主单，整批核导出权限，不能只返回部分有权明细。
	 */
	@ResponseExcel
	@GetMapping("/exportDetails")
	public List<WmsInBillDetailExcelVO> exportDetails(WmsInBillDetailQueryDTO params, Long[] ids) {
		return documents.exportDetails(Family.PURCHASE, params, ids);
	}
}
