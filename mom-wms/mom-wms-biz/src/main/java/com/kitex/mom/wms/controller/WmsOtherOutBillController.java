package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.mom.wms.service.WmsOtherBusinessTypes;
import com.kitex.mom.wms.service.other.WmsOtherDocumentAccess;
import com.kitex.mom.wms.service.other.WmsOtherDocumentService;
import org.springframework.beans.factory.annotation.Autowired;

import static com.kitex.mom.wms.service.other.WmsOtherDocumentAccess.Kind.OUT_BILL;

import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.enums.OtherOutBillTypeEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.service.outBill.WmsOutBillFactory;
import com.kitex.mom.wms.service.outBill.WmsOutBillHandler;
import com.kitex.mom.wms.vo.WmsOutBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsOutBillExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 其他出库单
 *
 * @author kitex
 * @date 2024-12-31 17:57:44
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/other/outBill")
@Tag(description = "WmsOtherOutBill", name = "其他出库单")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsOtherOutBillController {

	@Autowired
	private WmsOtherDocumentAccess access;
	@Autowired
	private WmsOtherDocumentService documents;

	// 其他出库通用类型
	private final String billType = OutBillTypeEnum.OTHER_OUT.getCode();

	// 其他出库所有类型，查询列表时的条件
	private final List<String> otherBillTypes = Stream.concat(Stream.of(billType),
			Arrays.stream(OtherOutBillTypeEnum.values()).map(OtherOutBillTypeEnum::getCode)).toList();

	/**
	 * 当前人员在原仓的其他出库动作能力；不接受客户端上传的授权结果。
	 */
	@PostMapping("/permissions")
	public R<Map<String, List<String>>> permissions(@RequestBody List<String> warehouseCodes) {
		return R.ok(access.outboundCapabilities(warehouseCodes));
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_view')")
	public R getWmsOutBillPage(@ParameterObject SearchPage page,
	                           @ParameterObject WmsInBillDetailQueryDTO wmsOutBill) {
		QueryWrapper<WmsOutBillEntity> wrapper = getMainQueryWrapper(wmsOutBill);
		access.restrictMain(OUT_BILL, wrapper, "view");
		WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
		return R.ok(handler.page(page.toMpPage(), wrapper));
	}

	/**
	 * 分页查询统计
	 */
	@Operation(summary = "分页查询统计", description = "分页查询统计")
	@GetMapping("/getCount")
	public R getCount(@ParameterObject WmsInBillDetailQueryDTO wmsOutBill) {
		QueryWrapper<WmsOutBillEntity> wrapper = getCountQueryWrapper(wmsOutBill);
		access.restrictMain(OUT_BILL, wrapper, "view");
		WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
		return R.ok(handler.getCount(wrapper));
	}

	/**
	 * 按完整原通知身份构造统计条件；现有业务类型与组织权限由原调用入口约束。
	 *
	 * @param wmsOutBill 查询参数，空通知号不筛选，非法通知号拒绝查询
	 * @return 主表查询条件，通知号以Long等值绑定
	 */
	public QueryWrapper<WmsOutBillEntity> getCountQueryWrapper(WmsInBillDetailQueryDTO wmsOutBill) {
		Long noticeId = wmsOutBill.noticeIdValue();
		QueryWrapper<WmsOutBillEntity> wrapper = new QueryWrapper<>();
		if (CollUtil.isNotEmpty(wmsOutBill.getCreateTime())) {
			wrapper.lambda().ge(BaseCore::getCreateTime, wmsOutBill.getCreateTime().get(0));
			if (wmsOutBill.getCreateTime().size() > 1) {
				wrapper.lambda().lt(BaseCore::getCreateTime, wmsOutBill.getCreateTime().get(1));
			}
		}
		wrapper.lambda()
				.like(StrUtil.isNotBlank(wmsOutBill.getBillNo()), WmsOutBillEntity::getBillNo,
						wmsOutBill.getBillNo())
				.eq(noticeId != null, WmsOutBillEntity::getNoticeId, noticeId)
				.eq(StrUtil.isNotBlank(wmsOutBill.getWarehouseCode()), WmsOutBillEntity::getWarehouseCode,
						wmsOutBill.getWarehouseCode())
				.like(StrUtil.isNotBlank(wmsOutBill.getCreateBy()), WmsOutBillEntity::getCreateBy,
						wmsOutBill.getCreateBy())
				.in(WmsOutBillEntity::getBillType, otherBillTypes)
				.eq(StrUtil.isNotBlank(wmsOutBill.getBillType()), WmsOutBillEntity::getBillType,
						wmsOutBill.getBillType());
		return wrapper;
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_view')")
	public R getWmsOutBillPageDetails(@ParameterObject SearchPage page,
	                                  @ParameterObject WmsInBillDetailQueryDTO params) {
		params.setBillTypes(otherBillTypes);
		access.restrictDetails(OUT_BILL, params, "view");
		WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
		return R.ok(handler.pageDetails(page.toMpPage(), params));
	}


	/**
	 * 通过id查询出库单
	 *
	 * @param id id
	 * @return R
	 */
	@Operation(summary = "通过id查询", description = "通过id查询")
	@GetMapping("/{id}")
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_view')")
	public R getById(@PathVariable("id") Long id) {
		return R.ok(documents.viewOutBill(id));
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_add')")
	public R save(@RequestBody WmsOutBillEntity wmsOutBill) {
		return R.ok(documents.createOutBill(wmsOutBill, false));
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_add_review')")
	public R addAndPass(@RequestBody WmsOutBillEntity wmsOutBill) {
		return R.ok(documents.createOutBill(wmsOutBill, true));
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_edit')")
	public R updateById(@RequestBody WmsOutBillEntity wmsOutBill) {
		return R.ok(documents.updateOutBill(wmsOutBill));
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_del')")
	public R removeById(@RequestBody String[] ids) {
		return R.ok(documents.removeOutBills(ids));
	}

	/**
	 * 提交出库单
	 *
	 * @param id id
	 * @return R
	 */
	@Operation(summary = "提交出库单", description = "提交出库单")
	@GetMapping("/commitBill")
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_commit')")
	public R commitBill(Long id) {
		return R.ok(documents.commitOutBill(id));
	}

	/**
	 * 审核通过出库单
	 *
	 * @param id id
	 * @return R
	 */
	@Operation(summary = "审核通过出库单", description = "审核通过出库单")
	@GetMapping("/passBill")
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_review')")
	public R passBill(Long id) {
		return R.ok(documents.passOutBill(id));
	}

	/**
	 * 审核驳回出库单
	 *
	 * @param params 出库单id、驳回原因
	 * @return R
	 */
	@Operation(summary = "审核驳回出库单", description = "审核驳回出库单")
	@PostMapping("/rejectBill")
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_review')")
	public R rejectBill(@RequestBody WmsOutBillEntity params) {
		return R.ok(documents.rejectOutBill(params));
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_export')")
	public List<WmsOutBillExcelVO> exportMain(WmsInBillDetailQueryDTO wmsOutNotice, Long[] ids) {
		QueryWrapper<WmsOutBillEntity> wrapper = new QueryWrapper<>();
		if (ArrayUtil.isNotEmpty(ids)) {
			wrapper.lambda().in(WmsOutBillEntity::getId, ids);
		} else {
			wrapper = getMainQueryWrapper(wmsOutNotice);
		}
		return documents.exportOutBills(wrapper, ids);
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_export')")
	public List<WmsOutBillDetailExcelVO> exportDetails(WmsInBillDetailQueryDTO params, Long[] ids) {
		return documents.exportOutBillDetails(params, ids);
	}

	/**
	 * 按完整原通知身份构造列表和主表导出条件；现有业务类型与组织权限由原调用入口约束。
	 *
	 * @param wmsOutBill 查询参数，空通知号不筛选，非法通知号拒绝查询
	 * @return 主表查询条件，通知号以Long等值绑定
	 */
	public QueryWrapper<WmsOutBillEntity> getMainQueryWrapper(WmsInBillDetailQueryDTO wmsOutBill) {
		Long noticeId = wmsOutBill.noticeIdValue();
		QueryWrapper<WmsOutBillEntity> wrapper = new QueryWrapper<>();
		if (CollUtil.isNotEmpty(wmsOutBill.getCreateTime())) {
			wrapper.lambda().ge(BaseCore::getCreateTime, wmsOutBill.getCreateTime().get(0));
			if (wmsOutBill.getCreateTime().size() > 1) {
				wrapper.lambda().lt(BaseCore::getCreateTime, wmsOutBill.getCreateTime().get(1));
			}
		}
		wrapper.lambda()
				.like(StrUtil.isNotBlank(wmsOutBill.getBillNo()), WmsOutBillEntity::getBillNo,
						wmsOutBill.getBillNo())
				.eq(noticeId != null, WmsOutBillEntity::getNoticeId, noticeId)
				.eq(StrUtil.isNotBlank(wmsOutBill.getState()), WmsOutBillEntity::getState,
						wmsOutBill.getState())
				.eq(StrUtil.isNotBlank(wmsOutBill.getWarehouseCode()), WmsOutBillEntity::getWarehouseCode,
						wmsOutBill.getWarehouseCode())
				.like(StrUtil.isNotBlank(wmsOutBill.getCreateBy()), WmsOutBillEntity::getCreateBy,
						wmsOutBill.getCreateBy())
				.in(WmsOutBillEntity::getBillType, otherBillTypes)
				.orderByAsc(WmsOutBillEntity::getState)
				.orderByDesc(BaseCore::getCreateTime);
		return wrapper;
	}
}
