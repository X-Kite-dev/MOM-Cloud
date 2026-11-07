package com.kitex.mom.wms.controller;

import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.service.bill.WmsOutBillApplicationService;
import com.kitex.mom.wms.vo.WmsOutBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsOutBillExcelVO;
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
import java.util.Map;

import com.kitex.mom.wms.service.bill.WmsOutBillAccess.Family;

/**
 * 通用出库管理入口。所有动作委派办理门面核实际工厂、原仓和动作权限，保留完整出库业务类型。
 * 单据状态、来源批准、库存和跨业务后续处理继续沿原类型处理器执行。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/outBill")
@Tag(description = "wmsOutBill", name = "出库单")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsOutBillController {

	private final WmsOutBillApplicationService documents;

	/**
	 * 分页查询当前工厂有查看权限的出库单。
	 *
	 * @param page       分页参数
	 * @param wmsOutBill 原通知、仓库、类型和状态等筛选条件
	 * @return 授权范围内分页，空授权返回零行
	 */
	@Operation(summary = "分页查询", description = "分页查询")
	@GetMapping("/page")
	public R getWmsOutBillPage(@ParameterObject SearchPage page,
	                           @ParameterObject WmsInBillDetailQueryDTO wmsOutBill) {
		return R.ok(documents.page(Family.WAREHOUSE, page.toMpPage(), wmsOutBill));
	}

	/**
	 * 按授权范围统计出库单状态，查询条件与原统计合同一致。
	 */
	@Operation(summary = "分页查询统计", description = "分页查询统计")
	@GetMapping("/getCount")
	public R getCount(@ParameterObject WmsInBillDetailQueryDTO wmsOutBill) {
		return R.ok(documents.count(Family.WAREHOUSE, wmsOutBill));
	}

	/**
	 * 查询出库明细，组织和仓库授权范围由服务端覆盖。
	 */
	@Operation(summary = "分页查询明细", description = "分页查询明细")
	@GetMapping("/pageDetails")
	public R getWmsOutBillPageDetails(@ParameterObject SearchPage page,
	                                  @ParameterObject WmsInBillDetailQueryDTO params) {
		return R.ok(documents.pageDetails(Family.WAREHOUSE, page.toMpPage(), params));
	}

	/**
	 * 按实际原单ID及原仓查看权限读取详情，不使用请求类型选择处理器。
	 */
	@Operation(summary = "通过id查询", description = "通过id查询")
	@GetMapping("/{id}")
	public R getById(@PathVariable("id") Long id) {
		return R.ok(documents.view(Family.WAREHOUSE, id));
	}

	/**
	 * 核原通知inout和通用新增权限后选库；没有标准映射的调拨/盘点通知沿原专用入口办理。
	 */
	@GetMapping("/getLiveStockByNoticeId")
	@Operation(summary = "根据通知单id查询实时库存", description = "根据通知单id查询实时库存")
	public R getLiveStockByNoticeId(Long id) {
		return R.ok(documents.stockForNotice(Family.WAREHOUSE, id));
	}

	/**
	 * 新增通用出库单，核目标仓新增权限及关联通知真实归属，原来源校验继续生效。
	 */
	@Operation(summary = "新增出库单", description = "新增出库单")
	@SysLog("新增出库单")
	@PostMapping
	public R save(@RequestBody WmsOutBillEntity wmsOutBill) {
		return R.ok(documents.create(Family.WAREHOUSE, wmsOutBill, false));
	}

	/**
	 * 新增并审核须有目标仓独立直接审核权限，原实物与来源规则不放宽。
	 */
	@Operation(summary = "新增出库单并直接审核通过", description = "新增出库单并直接审核通过")
	@SysLog("新增出库单并直接审核通过")
	@PostMapping("/addAndPass")
	public R addAndPass(@RequestBody WmsOutBillEntity wmsOutBill) {
		return R.ok(documents.create(Family.WAREHOUSE, wmsOutBill, true));
	}

	/**
	 * 编辑沿实际原单类型、原仓及目标仓权限，禁止改换类型或关联通知。
	 */
	@Operation(summary = "修改出库单", description = "修改出库单")
	@SysLog("修改出库单")
	@PutMapping
	public R updateById(@RequestBody WmsOutBillEntity wmsOutBill) {
		return R.ok(documents.update(Family.WAREHOUSE, wmsOutBill));
	}

	/**
	 * 全批先核实际原单与删除权限，再按原类型同事务删除；任一失败全部回滚。
	 */
	@Operation(summary = "通过id删除出库单", description = "通过id删除出库单")
	@SysLog("通过id删除出库单")
	@DeleteMapping
	public R removeById(@RequestBody String[] ids) {
		return R.ok(documents.remove(Family.WAREHOUSE, ids));
	}

	/**
	 * 核原仓提交权限后按实际原类型提交，保留原状态和来源预检。
	 */
	@Operation(summary = "提交出库单", description = "提交出库单")
	@GetMapping("/commitBill")
	public R commitBill(Long id) {
		return R.ok(documents.commit(Family.WAREHOUSE, id));
	}

	/**
	 * 核原仓审核权限后派发实际类型，不能使用默认处理器跳过专用业务后续处理。
	 */
	@Operation(summary = "审核通过出库单", description = "审核通过出库单")
	@GetMapping("/passBill")
	public R passBill(Long id) {
		return R.ok(documents.pass(Family.WAREHOUSE, id));
	}

	/**
	 * 驳回沿原单审核权限和实际类型，仅使用正文ID及驳回原因。
	 */
	@Operation(summary = "审核驳回出库单", description = "审核驳回出库单")
	@PostMapping("/rejectBill")
	public R rejectBill(@RequestBody WmsOutBillEntity params) {
		return R.ok(documents.reject(Family.WAREHOUSE, params));
	}

	/**
	 * 指定主单ID必须整批具有原仓导出权限；未选择时按当前授权查询导出。
	 */
	@ResponseExcel
	@GetMapping("/exportMain")
	public List<WmsOutBillExcelVO> exportMain(WmsInBillDetailQueryDTO wmsOutNotice, Long[] ids) {
		return documents.exportMain(Family.WAREHOUSE, wmsOutNotice, ids);
	}

	/**
	 * 明细ID与实际主单同组织同仓，整批核导出权限，不返回部分有权数据冒充完整导出。
	 */
	@ResponseExcel
	@GetMapping("/exportDetails")
	public List<WmsOutBillDetailExcelVO> exportDetails(WmsInBillDetailQueryDTO params, Long[] ids) {
		return documents.exportDetails(Family.WAREHOUSE, params, ids);
	}

	/**
	 * 当前入口的八项角色动作能力；不表示库存、质量、配置或工厂写授权已经放行。
	 */
	@PostMapping("/permissions")
	public R<Map<String, List<String>>> permissions(@RequestBody List<String> warehouseCodes) {
		return R.ok(documents.capabilities(Family.WAREHOUSE, warehouseCodes));
	}
}
