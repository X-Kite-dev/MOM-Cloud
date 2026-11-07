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
import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO;
import com.kitex.mom.wms.dto.WmsProductionNoticeProgressDTO;
import com.kitex.mom.wms.service.noticeAcceptance.WmsProductionNoticeAcceptanceService;
import com.kitex.mom.wms.service.noticeAcceptance.WmsProductionNoticeProgressService;
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
 * 生产领料出库通知单
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/produce/picking/outNotice")
@Tag(description = "ProductionPickingOutNotice", name = "生产领料出库通知单")
public class WmsProductionPickingOutNoticeController {

    private final WmsProductionNoticeAcceptanceService acceptance;
    private final WmsProductionNoticeProgressService progress;

    // 生产领料编码
    private final String billType = OutBillTypeEnum.PRODUCE_OUT.getCode();

    /**
     * 分页查询生产领料出库通知单
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询生产领料出库通知单", description = "分页查询生产领料出库通知单")
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
    public R getWmsInNoticeDetailPage(SearchPage page, WmsInNoticeDetailQueryDTO params) {
        params.setBillType(billType);
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        return R.ok(handler.pageDetails(page.toMpPage(), params));
    }

    /**
     * 新增生产领料出库通知单
     */
    @PostMapping("/add")
    @Operation(summary = "新增生产领料出库通知单", description = "新增生产领料出库通知单")
    public R<Boolean> add(@RequestBody WmsOutNoticeEntity wmsOutNoticeEntity) {
        wmsOutNoticeEntity.setBillType(billType);
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        Boolean result = handler.saveDeep(wmsOutNoticeEntity);
        return Boolean.TRUE.equals(result) ? R.ok(Boolean.TRUE) : R.failed();
    }

    @PostMapping("/v1/accept")
    @Operation(summary = "受理生产领料通知并返回永久回执")
    public R<WmsProductionNoticeAcceptanceDTO.Receipt> accept(
            @RequestBody WmsProductionNoticeAcceptanceDTO.Command command) {
        return R.ok(acceptance.accept(command));
    }

    @PostMapping("/v1/batch")
    @Operation(summary = "在同一WMS事务内受理多仓生产领料通知")
    public R<List<WmsProductionNoticeAcceptanceDTO.Receipt>> acceptBatch(
            @RequestBody List<WmsProductionNoticeAcceptanceDTO.Command> commands) {
        return R.ok(acceptance.acceptBatch(commands));
    }

    @GetMapping("/v1/source")
    public R<WmsProductionNoticeAcceptanceDTO.Source> receiptBySource(@RequestParam String warehouseCode,
                                                                      @RequestParam Long documentId,
                                                                      @RequestParam int sourceRevision) {
        return R.ok(acceptance.source(warehouseCode, documentId, sourceRevision));
    }

    @GetMapping("/v1/notices/{noticeId}/progress")
    @Operation(summary = "按当前仓库通知单核对受理、分配及关联出库单")
    public R<WmsProductionNoticeProgressDTO> progress(@PathVariable Long noticeId, @RequestParam String warehouseCode) {
        return R.ok(progress.progress(warehouseCode, noticeId));
    }

    @GetMapping("/v1/requests/{requestId}")
    public R<WmsProductionNoticeAcceptanceDTO.Lookup> receiptByRequest(@PathVariable String requestId,
                                                                       @RequestParam(defaultValue = "") String warehouseCode) {
        return R.ok(acceptance.lookup(warehouseCode, requestId));
    }

    @PostMapping("/v1/cancel")
    public R<WmsProductionNoticeAcceptanceDTO.Receipt> cancelSource(
            @RequestBody WmsProductionNoticeAcceptanceDTO.Cancel command) {
        return R.ok(acceptance.cancel(command));
    }

    /**
     * 修改生产领料出库通知单
     */
    @PutMapping("/edit")
    @Operation(summary = "修改生产领料出库通知单", description = "修改生产领料出库通知单")
    public R edit(@RequestBody WmsOutNoticeEntity wmsOutNoticeEntity) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        Boolean result = handler.updateDeep(wmsOutNoticeEntity);
        return result ? R.ok() : R.failed();
    }

    /**
     * 查询生产领料出库通知单详情
     */
    @GetMapping({"/detailsById", "/details"})
    @Operation(summary = "查询生产领料出库通知单详情", description = "查询生产领料出库通知单详情")
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
     * 作废生产领料出库通知单
     */
    @GetMapping("/voidObj")
    @Operation(summary = "作废生产领料出库通知单", description = "作废生产领料出库通知单")
    public R voidObj(@RequestParam Long id) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        handler.voidObjs(id);
        return R.ok();
    }

    /**
     * 作废生产领料出库通知单-通过业务单据
     */
    @GetMapping("/voidObjByDocumentId")
    @Operation(summary = "作废生产领料出库通知单-通过业务单据", description = "作废生产领料出库通知单-通过业务单据")
    public R voidObjByDocumentId(@RequestParam Long documentId, @RequestParam String documentTable) {
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(billType);
        return R.ok(handler.voidObjByDocumentId(documentId, documentTable));
    }

    /**
     * 删除生产领料出库通知单
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除生产领料出库通知单", description = "删除生产领料出库通知单")
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
