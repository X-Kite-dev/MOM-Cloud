package com.kitex.mom.wms.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
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

import java.util.List;

/**
 * 生产领料出库单
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

    // 生产领料出库编码
    private final String billType = OutBillTypeEnum.PRODUCE_OUT.getCode();

    /**
     * 分页查询生产领料出库单
     *
     * @param page       分页对象
     * @param wmsOutBill 出库单
     * @return
     */
    @Operation(summary = "分页查询生产领料出库单", description = "分页查询生产领料出库单")
    @GetMapping("/page")
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_view')")
    public R getWmsOutBillPage(@ParameterObject SearchPage page, @ParameterObject WmsInBillDetailQueryDTO wmsOutBill) {
        QueryWrapper<WmsOutBillEntity> wrapper = getMainQueryWrapper(wmsOutBill);
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
        return R.ok(handler.page(page.toMpPage(), wrapper));
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
    public R getWmsOutBillPageDetails(@ParameterObject SearchPage page, @ParameterObject WmsInBillDetailQueryDTO params) {
        params.setBillType(billType);
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
        return R.ok(handler.pageDetails(page.toMpPage(), params));
    }

    /**
     * 通过id查询出库单(含明细、最大库存)
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询出库单(含明细、最大库存)", description = "通过id查询出库单(含明细、最大库存)")
    @GetMapping("/{id}")
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_view')")
    public R getById(@PathVariable("id") Long id) {
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
        return R.ok(handler.selectById(id));
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
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
        return R.ok(handler.getDeepById(id));
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
        wmsOutBill.setBillType(billType);
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
        return R.ok(handler.saveDeep(wmsOutBill));
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
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
        return R.ok(handler.addAndPass(wmsOutBill));
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
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
        return R.ok(handler.updateDeep(wmsOutBill));
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
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
        return R.ok(handler.removeDeep(ids));
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
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
        return R.ok(handler.commitBill(id));
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
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
        return R.ok(handler.passBill(id));
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
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
        return R.ok(handler.rejectBill(params));
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
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsOutBill_export')")
    public List<WmsOutBillDetailExcelVO> exportDetails(WmsInBillDetailQueryDTO params, Long[] ids) {
        params.setBillType(billType);
        WmsOutBillHandler handler = WmsOutBillFactory.getHandler(billType);
        return handler.listDetails(params, ids);
    }

    public QueryWrapper<WmsOutBillEntity> getMainQueryWrapper(WmsInBillDetailQueryDTO wmsOutBill) {
        QueryWrapper<WmsOutBillEntity> wrapper = new QueryWrapper<>();
        if (CollUtil.isNotEmpty(wmsOutBill.getCreateTime())) {
            wrapper.lambda().ge(BaseCore::getCreateTime, wmsOutBill.getCreateTime().get(0));
            if (wmsOutBill.getCreateTime().size() > 1) {
                wrapper.lambda().lt(BaseCore::getCreateTime, wmsOutBill.getCreateTime().get(1));
            }
        }
        wrapper.lambda().like(StrUtil.isNotBlank(wmsOutBill.getBillNo()), WmsOutBillEntity::getBillNo, wmsOutBill.getBillNo())
                .like(StrUtil.isNotBlank(wmsOutBill.getNoticeId()), WmsOutBillEntity::getNoticeId, wmsOutBill.getNoticeId())
                .eq(StrUtil.isNotBlank(wmsOutBill.getState()), WmsOutBillEntity::getState, wmsOutBill.getState())
                .in(CollUtil.isNotEmpty(wmsOutBill.getStateList()), WmsOutBillEntity::getState, wmsOutBill.getStateList())
                .eq(StrUtil.isNotBlank(wmsOutBill.getWarehouseCode()), WmsOutBillEntity::getWarehouseCode, wmsOutBill.getWarehouseCode())
                .like(StrUtil.isNotBlank(wmsOutBill.getCreateBy()), WmsOutBillEntity::getCreateBy, wmsOutBill.getCreateBy())
                .eq(WmsOutBillEntity::getBillType, billType)
                .orderByAsc(WmsOutBillEntity::getState)
                .orderByDesc(BaseCore::getCreateTime);
        return wrapper;
    }
}