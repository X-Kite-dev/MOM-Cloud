package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.dto.WmsInNoticeQueryDTO;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.service.outNotice.WmsOutNoticeFactory;
import com.kitex.mom.wms.service.outNotice.WmsOutNoticeHandler;
import com.kitex.mom.wms.vo.WmsOutNoticeDetailExcelVO;
import com.kitex.mom.wms.vo.WmsOutNoticeExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 销售出库通知单
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/sales/outbound/notice")
@Tag(description = "SalesOutboundNoticeController", name = "销售出库通知单")
public class WmsSalesOutNoticeController {

    // 销售出库编码
    private final String billType = OutBillTypeEnum.SALES_OUT.getCode();

    /**
     * 分页查询销售出库通知单
     */
    @Operation(summary = "分页查询销售出库通知单", description = "分页查询销售出库通知单")
    @GetMapping("/page")
    public R<Page<WmsOutNoticeEntity>> page(SearchPage page, WmsInNoticeQueryDTO queryDTO) {
        QueryWrapper<WmsOutNoticeEntity> wrapper = getMainQueryWrapper(queryDTO);
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        Page result = handler.page(page.toMpPage(), wrapper);
        return R.ok(result);
    }

    @GetMapping("/getCount")
    @Operation(summary = "分页查询生产领料出库通知单", description = "分页查询生产领料出库通知单")
    public R<Map<String, Long>> getCount(WmsInNoticeQueryDTO queryDTO) {
        QueryWrapper<WmsOutNoticeEntity> wrapper = getCountQueryWrapper(queryDTO);
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
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
    public R getDetailPage(SearchPage page, WmsInNoticeDetailQueryDTO params) {
        params.setBillType(billType);
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        return R.ok(handler.pageDetails(page.toMpPage(), params));
    }

    /**
     * 新增销售出库通知单
     */
    @PostMapping("/add")
    @Operation(summary = "新增销售出库通知单", description = "新增销售出库通知单")
    public R add(@RequestBody WmsOutNoticeEntity wmsOutNoticeEntity) {
        wmsOutNoticeEntity.setBillType(billType);
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        Boolean result = handler.saveDeep(wmsOutNoticeEntity);
        return result ? R.ok() : R.failed();
    }

    /**
     * 修改销售出库通知单
     */
    @PutMapping("/edit")
    @Operation(summary = "修改销售出库通知单", description = "修改销售出库通知单")
    public R edit(@RequestBody WmsOutNoticeEntity wmsOutNoticeEntity) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        Boolean result = handler.updateDeep(wmsOutNoticeEntity);
        return result ? R.ok() : R.failed();
    }

    /**
     * 查询销售出库通知单详情
     */
    @GetMapping("/detailsById")
    @Operation(summary = "查询销售出库通知单详情", description = "查询销售出库通知单详情")
    public R details(Long id) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        WmsOutNoticeEntity wmsOutNoticeEntity = handler.selectById(id);
        return R.ok(wmsOutNoticeEntity);
    }

    /**
     * 根据通知单id查询实时库存
     */
    @GetMapping("/getLiveStockByNoticeId")
    @Operation(summary = "根据通知单id查询实时库存", description = "根据通知单id查询实时库存")
    public R getLiveStockByNoticeId(Long id) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        WmsOutBillEntity wmsOutNoticeEntity = handler.getLiveStockByNoticeId(id);
        return R.ok(wmsOutNoticeEntity);
    }

    /**
     * 作废
     */
    @GetMapping("/voidObj")
    @Operation(summary = "作废", description = "作废")
    public R voidObj(@RequestParam Long id) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        handler.voidObjs(id);
        return R.ok();
    }

    /**
     * 删除销售出库通知单
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除销售出库通知单", description = "删除销售出库通知单")
    public R delete(@RequestBody String[] ids) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        Boolean result = handler.removeDeep(ids);
        return result ? R.ok() : R.failed();
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutNotice_export')")
    public List<WmsOutNoticeExcelVO> exportMain(WmsInNoticeQueryDTO wmsOutNotice, Long[] ids) {
        QueryWrapper<WmsOutNoticeEntity> wrapper = new QueryWrapper<>();
        if (ArrayUtil.isNotEmpty(ids)) {
            wrapper.lambda().in(WmsOutNoticeEntity::getId, ids);
        } else {
            wrapper = getMainQueryWrapper(wmsOutNotice);
        }
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        return handler.list(wrapper);
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutNotice_export')")
    public List<WmsOutNoticeDetailExcelVO> exportDetails(WmsInNoticeDetailQueryDTO params, Long[] ids) {
        params.setBillType(billType);
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        return handler.listDetails(params, ids);
    }

	/**
	 * 按完整原通知身份构造通知列表和主表导出条件；不改变既有业务类型、状态和组织约束。
	 *
	 * @param queryDTO 查询参数，空通知号不筛选，非法通知号拒绝查询
	 * @return 通知主表查询条件，通知号以Long等值绑定
	 */
	public QueryWrapper<WmsOutNoticeEntity> getMainQueryWrapper(WmsInNoticeQueryDTO queryDTO) {
		Long noticeId = queryDTO.noticeIdValue();
		QueryWrapper<WmsOutNoticeEntity> wrapper = new QueryWrapper<>();
		if (CollUtil.isNotEmpty(queryDTO.getCreateTime())) {
			wrapper.lambda().ge(BaseCore::getCreateTime, queryDTO.getCreateTime().get(0));
			if (queryDTO.getCreateTime().size() > 1) {
				wrapper.lambda().lt(BaseCore::getCreateTime, queryDTO.getCreateTime().get(1));
			}
		}
		wrapper.lambda().eq(noticeId != null, WmsOutNoticeEntity::getId, noticeId)
				.eq(StrUtil.isNotBlank(queryDTO.getState()), WmsOutNoticeEntity::getState, queryDTO.getState())
				.like(StrUtil.isNotBlank(queryDTO.getWarehouseCode()), WmsOutNoticeEntity::getWarehouseCode,
						queryDTO.getWarehouseCode())
				.like(StrUtil.isNotBlank(queryDTO.getCreateBy()), BaseCore::getCreateBy, queryDTO.getCreateBy())
				.eq(WmsOutNoticeEntity::getBillType, billType)
				.orderByAsc(WmsOutNoticeEntity::getState)
				.orderByDesc(BaseCore::getCreateTime);
		return wrapper;
	}

	/**
	 * 按完整原通知身份构造统计条件；不改变既有业务类型、状态和组织约束。
	 *
	 * @param queryDTO 查询参数，空通知号不筛选，非法通知号拒绝查询
	 * @return 通知主表查询条件，通知号以Long等值绑定
	 */
	public QueryWrapper<WmsOutNoticeEntity> getCountQueryWrapper(WmsInNoticeQueryDTO queryDTO) {
		Long noticeId = queryDTO.noticeIdValue();
		QueryWrapper<WmsOutNoticeEntity> wrapper = new QueryWrapper<>();
		if (CollUtil.isNotEmpty(queryDTO.getCreateTime())) {
			wrapper.lambda().ge(BaseCore::getCreateTime, queryDTO.getCreateTime().get(0));
			if (queryDTO.getCreateTime().size() > 1) {
				wrapper.lambda().lt(BaseCore::getCreateTime, queryDTO.getCreateTime().get(1));
			}
		}
		wrapper.lambda().eq(noticeId != null, WmsOutNoticeEntity::getId, noticeId)
				.like(StrUtil.isNotBlank(queryDTO.getWarehouseCode()), WmsOutNoticeEntity::getWarehouseCode,
						queryDTO.getWarehouseCode())
				.like(StrUtil.isNotBlank(queryDTO.getCreateBy()), BaseCore::getCreateBy, queryDTO.getCreateBy())
				.eq(WmsOutNoticeEntity::getBillType, billType);
		return wrapper;
	}

}
