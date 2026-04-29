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
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.OtherInBillTypeEnum;
import com.kitex.mom.wms.service.inBill.WmsInBillFactory;
import com.kitex.mom.wms.service.inBill.WmsInBillHandler;
import com.kitex.mom.wms.vo.WmsInBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsInBillExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * 其他入库单
 *
 * @author kitex
 * @date 2024-12-31 17:59:13
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/other/inBill")
@Tag(description = "WmsProductInBillController", name = "其他入库单")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsOtherInBillController {

    // 其他入库通用类型
    private final String billType = InBillTypeEnum.OTHER_IN.getCode();

    // 其他入库所有类型，查询列表时的条件
    private final List<String> otherBillTypes = Arrays.stream(OtherInBillTypeEnum.values()).map(OtherInBillTypeEnum::getCode).toList();

    /**
     * 分页查询
     *
     * @param page      分页对象
     * @param wmsInBill 入库单
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInBill_view')")
    public R getWmsInBillPage(@ParameterObject SearchPage page, @ParameterObject WmsInBillDetailQueryDTO wmsInBill) {
        QueryWrapper<WmsInBillEntity> wrapper = getMainQueryWrapper(wmsInBill);
        WmsInBillHandler handler = WmsInBillFactory.getHandler(billType);
        return R.ok(handler.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询统计
     */
    @Operation(summary = "分页查询统计", description = "分页查询统计")
    @GetMapping("/getCount")
    public R getCount(@ParameterObject WmsInBillDetailQueryDTO wmsInBill) {
        QueryWrapper<WmsInBillEntity> wrapper = getCountQueryWrapper(wmsInBill);
        WmsInBillHandler handler = WmsInBillFactory.getHandler(wmsInBill.getBillType());
        return R.ok(handler.getCount(wrapper));
    }

    public QueryWrapper<WmsInBillEntity> getCountQueryWrapper(WmsInBillDetailQueryDTO wmsInBill) {
        QueryWrapper<WmsInBillEntity> wrapper = new QueryWrapper<>();
        if (CollUtil.isNotEmpty(wmsInBill.getCreateTime())) {
            wrapper.lambda().ge(BaseCore::getCreateTime, wmsInBill.getCreateTime().get(0));
            if (wmsInBill.getCreateTime().size() > 1) {
                wrapper.lambda().lt(BaseCore::getCreateTime, wmsInBill.getCreateTime().get(1));
            }
        }
        wrapper.lambda().like(StrUtil.isNotBlank(wmsInBill.getBillNo()), WmsInBillEntity::getBillNo, wmsInBill.getBillNo())
                .like(StrUtil.isNotBlank(wmsInBill.getNoticeId()), WmsInBillEntity::getNoticeId, wmsInBill.getNoticeId())
                .eq(StrUtil.isNotBlank(wmsInBill.getWarehouseCode()), WmsInBillEntity::getWarehouseCode, wmsInBill.getWarehouseCode())
                .like(StrUtil.isNotBlank(wmsInBill.getCreateBy()), WmsInBillEntity::getCreateBy, wmsInBill.getCreateBy())
                .in(StrUtil.isBlank(wmsInBill.getBillType()), WmsInBillEntity::getBillType, otherBillTypes)
                .eq(StrUtil.isNotBlank(wmsInBill.getBillType()), WmsInBillEntity::getBillType, wmsInBill.getBillType());
        return wrapper;
    }

    /**
     * 分页查询明细
     *
     * @param page   分页对象
     * @param params 入库单
     * @return
     */
    @Operation(summary = "分页查询明细", description = "分页查询明细")
    @GetMapping("/pageDetails")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInBill_view')")
    public R getWmsInBillPageDetails(@ParameterObject SearchPage page, @ParameterObject WmsInBillDetailQueryDTO params) {
        params.setBillTypes(otherBillTypes);
        WmsInBillHandler handler = WmsInBillFactory.getHandler(billType);
        return R.ok(handler.pageDetails(page.toMpPage(), params));
    }


    /**
     * 通过id查询入库单
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInBill_view')")
    public R getById(@PathVariable("id") Long id) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(billType);
        return R.ok(handler.selectById(id));
    }

    /**
     * 新增入库单
     *
     * @param wmsInBill 入库单
     * @return R
     */
    @Operation(summary = "新增入库单", description = "新增入库单")
    @SysLog("新增入库单")
    @PostMapping
//	@PreAuthorize("@pms.hasPermission('wms_wmsInBill_add')")
    public R save(@RequestBody WmsInBillEntity wmsInBill) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(wmsInBill.getBillType());
        return R.ok(handler.saveDeep(wmsInBill));
    }

    /**
     * 新增入库单并直接审核通过
     *
     * @param wmsInBill 入库单
     * @return R
     */
    @Operation(summary = "新增入库单并直接审核通过", description = "新增入库单并直接审核通过")
    @SysLog("新增入库单并直接审核通过")
    @PostMapping("/addAndPass")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInBill_add_review')")
    public R addAndPass(@RequestBody WmsInBillEntity wmsInBill) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(wmsInBill.getBillType());
        return R.ok(handler.addAndPass(wmsInBill));
    }

    /**
     * 修改入库单
     *
     * @param wmsInBill 入库单
     * @return R
     */
    @Operation(summary = "修改入库单", description = "修改入库单")
    @SysLog("修改入库单")
    @PutMapping
//	@PreAuthorize("@pms.hasPermission('wms_wmsInBill_edit')")
    public R updateById(@RequestBody WmsInBillEntity wmsInBill) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(wmsInBill.getBillType());
        return R.ok(handler.updateDeep(wmsInBill));
    }

    /**
     * 通过id删除入库单
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除入库单", description = "通过id删除入库单")
    @SysLog("通过id删除入库单")
    @DeleteMapping
//	@PreAuthorize("@pms.hasPermission('wms_wmsInBill_del')")
    public R removeById(@RequestBody String[] ids) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(billType);
        return R.ok(handler.removeDeep(ids));
    }

    /**
     * 提交入库单
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "提交入库单", description = "提交入库单")
    @GetMapping("/commitBill")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInBill_commit')")
    public R commitBill(Long id) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(billType);
        return R.ok(handler.commitBill(id));
    }

    /**
     * 审核通过入库单
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "审核通过入库单", description = "审核通过入库单")
    @GetMapping("/passBill")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInBill_review')")
    public R passBill(Long id) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(billType);
        return R.ok(handler.passBill(id));
    }

    /**
     * 审核驳回入库单
     *
     * @param params 入库单id、原因
     * @return R
     */
    @Operation(summary = "审核驳回入库单", description = "审核驳回入库单")
    @PostMapping("/rejectBill")
//	@PreAuthorize("@pms.hasPermission('wms_wmsInBill_review')")
    public R rejectBill(@RequestBody WmsInBillEntity params) {
        WmsInBillHandler handler = WmsInBillFactory.getHandler(billType);
        return R.ok(handler.rejectBill(params));
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsInBill_export')")
    public List<WmsInBillExcelVO> exportMain(WmsInBillDetailQueryDTO wmsInNotice, Long[] ids) {
        QueryWrapper<WmsInBillEntity> wrapper = new QueryWrapper<>();
        if (ArrayUtil.isNotEmpty(ids)) {
            wrapper.lambda().in(WmsInBillEntity::getId, ids);
        } else {
            wrapper = getMainQueryWrapper(wmsInNotice);
        }
        WmsInBillHandler handler = WmsInBillFactory.getHandler(billType);
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsInBill_export')")
    public List<WmsInBillDetailExcelVO> exportDetails(WmsInBillDetailQueryDTO params, Long[] ids) {
        params.setBillTypes(otherBillTypes);
        WmsInBillHandler handler = WmsInBillFactory.getHandler(billType);
        return handler.listDetails(params, ids);
    }

    public QueryWrapper<WmsInBillEntity> getMainQueryWrapper(WmsInBillDetailQueryDTO wmsInBill) {
        QueryWrapper<WmsInBillEntity> wrapper = new QueryWrapper<>();
        if (CollUtil.isNotEmpty(wmsInBill.getCreateTime())) {
            wrapper.lambda().ge(BaseCore::getCreateTime, wmsInBill.getCreateTime().get(0));
            if (wmsInBill.getCreateTime().size() > 1) {
                wrapper.lambda().lt(BaseCore::getCreateTime, wmsInBill.getCreateTime().get(1));
            }
        }
        wrapper.lambda().like(StrUtil.isNotBlank(wmsInBill.getBillNo()), WmsInBillEntity::getBillNo, wmsInBill.getBillNo())
                .like(StrUtil.isNotBlank(wmsInBill.getNoticeId()), WmsInBillEntity::getNoticeId, wmsInBill.getNoticeId())
                .eq(StrUtil.isNotBlank(wmsInBill.getState()), WmsInBillEntity::getState, wmsInBill.getState())
                .like(StrUtil.isNotBlank(wmsInBill.getWarehouseCode()), WmsInBillEntity::getWarehouseCode, wmsInBill.getWarehouseCode())
                .like(StrUtil.isNotBlank(wmsInBill.getCreateBy()), WmsInBillEntity::getCreateBy, wmsInBill.getCreateBy())
                .eq(StrUtil.isNotBlank(wmsInBill.getBillType()), WmsInBillEntity::getBillType, wmsInBill.getBillType())
                .in(StrUtil.isBlank(wmsInBill.getBillType()), WmsInBillEntity::getBillType, otherBillTypes)
                .orderByAsc(WmsInBillEntity::getState)
                .orderByDesc(BaseCore::getCreateTime);
        return wrapper;
    }
}