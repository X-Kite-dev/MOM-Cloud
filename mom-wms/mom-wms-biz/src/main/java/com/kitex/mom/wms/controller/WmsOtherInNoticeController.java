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

import static com.kitex.mom.wms.service.other.WmsOtherDocumentAccess.Kind.IN_NOTICE;

import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.dto.WmsInNoticeQueryDTO;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.OtherInBillTypeEnum;
import com.kitex.mom.wms.service.inNotice.WmsInNoticeFactory;
import com.kitex.mom.wms.service.inNotice.WmsInNoticeHandler;
import com.kitex.mom.wms.vo.WmsInNoticeDetailExcelVO;
import com.kitex.mom.wms.vo.WmsInNoticeExcelVO;
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
 * 其他入库通知单
 *
 * @author kitex
 * @date 2024-12-31 17:59:18
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/other/inNotice")
@Tag(description = "WmsOtherInNoticeController", name = "其他入库通知单")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsOtherInNoticeController {

	@Autowired
	private WmsOtherDocumentAccess access;
	@Autowired
	private WmsOtherDocumentService documents;

	// 其他入库通用类型
	private final String billType = InBillTypeEnum.OTHER_IN.getCode();

	// 其他入库所有类型，查询列表时的条件
	private final List<String> otherBillTypes = Stream.concat(Stream.of(billType),
			Arrays.stream(OtherInBillTypeEnum.values()).map(OtherInBillTypeEnum::getCode)).toList();

	/**
	 * 分页查询主表
	 *
	 * @param page        分页对象
	 * @param wmsInNotice 查询条件
	 * @return
	 */
	@Operation(summary = "分页查询", description = "分页查询")
	@GetMapping("/page")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInNotice_view')")
	public R getWmsInNoticePage(@ParameterObject SearchPage page,
	                            @ParameterObject WmsInNoticeQueryDTO wmsInNotice) {
		QueryWrapper<WmsInNoticeEntity> wrapper = getMainQueryWrapper(wmsInNotice);
		access.restrictMain(IN_NOTICE, wrapper, "view");
		WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(billType);
		return R.ok(handler.page(page.toMpPage(), wrapper));
	}

	/**
	 * 分页查询主表统计
	 */
	@Operation(summary = "分页查询主表统计", description = "分页查询主表统计")
	@GetMapping("/getCount")
	public R<Map<String, Long>> getCount(@ParameterObject WmsInNoticeQueryDTO wmsInNotice) {
		QueryWrapper<WmsInNoticeEntity> wrapper = getCountQueryWrapper(wmsInNotice);
		access.restrictMain(IN_NOTICE, wrapper, "view");
		WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(billType);
		return R.ok(handler.getCount(wrapper));
	}


	/**
	 * 分页查询明细
	 *
	 * @param page   分页对象
	 * @param params 查询条件
	 * @return
	 */
	@Operation(summary = "分页查询明细", description = "分页查询明细")
	@GetMapping("/pageDetails")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInNotice_view')")
	public R getWmsInNoticeDetailPage(@ParameterObject SearchPage page,
	                                  @ParameterObject WmsInNoticeDetailQueryDTO params) {
		params.setBillTypes(otherBillTypes);
		access.restrictDetails(IN_NOTICE, params, "view");
		WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(billType);
		return R.ok(handler.pageDetails(page.toMpPage(), params));
	}

	/**
	 * 查询入库通知单详情
	 *
	 * @return R  对象
	 */
	@Operation(summary = "查询入库通知单详情", description = "查询入库通知单详情")
	@GetMapping("/detailsById")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInNotice_view')")
	public R getDetails(@RequestParam Long id) {
		return R.ok(documents.viewInNotice(id));
	}

	/**
	 * 新增入库通知单
	 *
	 * @return
	 */
	@Operation(summary = "新增入库通知单", description = "新增入库通知单")
	@SysLog("新增入库通知单")
	@PostMapping("/addInNotice")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInNotice_add')")
	public R save(@RequestBody WmsInNoticeEntity wmsInNotice) {
		WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(
				WmsOtherBusinessTypes.requireInbound(wmsInNotice == null ? null : wmsInNotice.getBillType()));
		access.requireCreate(IN_NOTICE, wmsInNotice == null ? null : wmsInNotice.getWarehouseCode(), "add");
		return R.ok(handler.saveDeep(wmsInNotice));
	}

	/**
	 * 作废
	 */
	@GetMapping("/voidObj")
	public R voidObj(@RequestParam Long id) {
		documents.voidInNotice(id);
		return R.ok();
	}

	/**
	 * 修改入库通知单
	 *
	 * @param wmsInNotice 入库通知单
	 * @return R
	 */
	@Operation(summary = "修改入库通知单", description = "修改入库通知单")
	@SysLog("修改入库通知单")
	@PutMapping
//	@PreAuthorize("@pms.hasPermission('wms_wmsInNotice_edit')")
	public R updateById(@RequestBody WmsInNoticeEntity wmsInNotice) {
		return R.ok(documents.updateInNotice(wmsInNotice));
	}

	/**
	 * 通过id删除入库通知单
	 *
	 * @param ids id列表
	 * @return R
	 */
	@Operation(summary = "通过id删除入库通知单", description = "通过id删除入库通知单")
	@SysLog("通过id删除入库通知单")
	@DeleteMapping
//	@PreAuthorize("@pms.hasPermission('wms_wmsInNotice_del')")
	public R removeById(@RequestBody String[] ids) {
		return R.ok(documents.removeInNotices(ids));
	}

	/**
	 * 导出主表 excel 表格
	 *
	 * @param wmsInNotice 查询条件
	 * @param ids         导出指定ID
	 * @return excel 文件流
	 */
	@ResponseExcel
	@GetMapping("/exportMain")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInNotice_export')")
	public List<WmsInNoticeExcelVO> exportMain(WmsInNoticeQueryDTO wmsInNotice, Long[] ids) {
		QueryWrapper<WmsInNoticeEntity> wrapper = new QueryWrapper<>();
		if (ArrayUtil.isNotEmpty(ids)) {
			wrapper.lambda().in(WmsInNoticeEntity::getId, ids);
		} else {
			wrapper = getMainQueryWrapper(wmsInNotice);
		}
		return documents.exportInNotices(wrapper, ids);
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsInNotice_export')")
	public List<WmsInNoticeDetailExcelVO> exportDetails(WmsInNoticeDetailQueryDTO params, Long[] ids) {
		return documents.exportInNoticeDetails(params, ids);
	}

	/**
	 * 按完整原通知身份构造通知列表和主表导出条件；不改变既有业务类型、状态和组织约束。
	 *
	 * @param wmsInNotice 查询参数，空通知号不筛选，非法通知号拒绝查询
	 * @return 通知主表查询条件，通知号以Long等值绑定
	 */
	public QueryWrapper<WmsInNoticeEntity> getMainQueryWrapper(WmsInNoticeQueryDTO wmsInNotice) {
		Long noticeId = wmsInNotice.noticeIdValue();
		QueryWrapper<WmsInNoticeEntity> wrapper = new QueryWrapper<>();
		if (CollUtil.isNotEmpty(wmsInNotice.getCreateTime())) {
			wrapper.lambda().ge(BaseCore::getCreateTime, wmsInNotice.getCreateTime().get(0));
			if (wmsInNotice.getCreateTime().size() > 1) {
				wrapper.lambda().lt(BaseCore::getCreateTime, wmsInNotice.getCreateTime().get(1));
			}
		}
		wrapper.lambda()
				.eq(noticeId != null, WmsInNoticeEntity::getId, noticeId)
				.eq(StrUtil.isNotBlank(wmsInNotice.getState()), WmsInNoticeEntity::getState,
						wmsInNotice.getState())
				.like(StrUtil.isNotBlank(wmsInNotice.getWarehouseCode()), WmsInNoticeEntity::getWarehouseCode,
						wmsInNotice.getWarehouseCode())
				.like(StrUtil.isNotBlank(wmsInNotice.getCreateBy()), BaseCore::getCreateBy,
						wmsInNotice.getCreateBy())
				.in(WmsInNoticeEntity::getBillType, otherBillTypes)
				.orderByAsc(WmsInNoticeEntity::getState)
				.orderByDesc(BaseCore::getCreateTime);
		return wrapper;
	}

	/**
	 * 按完整原通知身份构造统计条件；不改变既有业务类型、状态和组织约束。
	 *
	 * @param wmsInNotice 查询参数，空通知号不筛选，非法通知号拒绝查询
	 * @return 通知主表查询条件，通知号以Long等值绑定
	 */
	public QueryWrapper<WmsInNoticeEntity> getCountQueryWrapper(WmsInNoticeQueryDTO wmsInNotice) {
		Long noticeId = wmsInNotice.noticeIdValue();
		QueryWrapper<WmsInNoticeEntity> wrapper = new QueryWrapper<>();
		if (CollUtil.isNotEmpty(wmsInNotice.getCreateTime())) {
			wrapper.lambda().ge(BaseCore::getCreateTime, wmsInNotice.getCreateTime().get(0));
			if (wmsInNotice.getCreateTime().size() > 1) {
				wrapper.lambda().lt(BaseCore::getCreateTime, wmsInNotice.getCreateTime().get(1));
			}
		}
		wrapper.lambda()
				.eq(noticeId != null, WmsInNoticeEntity::getId, noticeId)
				.like(StrUtil.isNotBlank(wmsInNotice.getWarehouseCode()), WmsInNoticeEntity::getWarehouseCode,
						wmsInNotice.getWarehouseCode())
				.like(StrUtil.isNotBlank(wmsInNotice.getCreateBy()), BaseCore::getCreateBy,
						wmsInNotice.getCreateBy())
				.in(WmsInNoticeEntity::getBillType, otherBillTypes);
		return wrapper;
	}
}
