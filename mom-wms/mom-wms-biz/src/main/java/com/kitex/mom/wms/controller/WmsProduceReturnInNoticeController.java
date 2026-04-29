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
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.dto.WmsInNoticeQueryDTO;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
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

import java.util.List;

/**
 * 生产退料入库通知单
 *
 * @author kitex
 * @date 2024-12-31 11:16:30
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsProduceReturn/inNotice")
@Tag(description = "wmsProduceReturnInNotice", name = "生产退料入库通知单")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsProduceReturnInNoticeController {

    private final String billType = InBillTypeEnum.RETURN.getCode();

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
    public R getWmsInNoticePage(@ParameterObject SearchPage page, @ParameterObject WmsInNoticeQueryDTO wmsInNotice) {
        QueryWrapper<WmsInNoticeEntity> wrapper = getMainQueryWrapper(wmsInNotice);
        WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(billType);
        return R.ok(handler.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询主表统计
     */
    @Operation(summary = "分页查询主表统计", description = "分页查询主表统计")
    @GetMapping("/getCount")
    public R getCount(@ParameterObject WmsInNoticeQueryDTO wmsInNotice) {
        QueryWrapper<WmsInNoticeEntity> wrapper = getCountQueryWrapper(wmsInNotice);
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
    public R getWmsInNoticeDetailPage(@ParameterObject SearchPage page, @ParameterObject WmsInNoticeDetailQueryDTO params) {
        params.setBillType(billType);
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
        WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(billType);
        return R.ok(handler.selectById(id));
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
        wmsInNotice.setBillType(billType);
        WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(billType);
        return R.ok(handler.saveDeep(wmsInNotice));
    }

    /**
     * 作废
     */
    @GetMapping("/voidObj")
    public R voidObj(@RequestParam Long id) {
        WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(billType);
        handler.voidObjs(id);
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
        WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(billType);
        return R.ok(handler.updateDeep(wmsInNotice));
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
        WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(billType);
        return R.ok(handler.removeDeep(ids));
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
        WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(billType);
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
//	@PreAuthorize("@pms.hasPermission('wms_wmsInNotice_export')")
    public List<WmsInNoticeDetailExcelVO> exportDetails(WmsInNoticeDetailQueryDTO params, Long[] ids) {
        params.setBillType(billType);
        WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(billType);
        return handler.listDetails(params, ids);
    }

    public QueryWrapper<WmsInNoticeEntity> getMainQueryWrapper(WmsInNoticeQueryDTO wmsInNotice) {
        QueryWrapper<WmsInNoticeEntity> wrapper = new QueryWrapper<>();
        if (CollUtil.isNotEmpty(wmsInNotice.getCreateTime())) {
            wrapper.lambda().ge(BaseCore::getCreateTime, wmsInNotice.getCreateTime().get(0));
            if (wmsInNotice.getCreateTime().size() > 1) {
                wrapper.lambda().lt(BaseCore::getCreateTime, wmsInNotice.getCreateTime().get(1));
            }
        }
        wrapper.lambda().like(StrUtil.isNotBlank(wmsInNotice.getId()), WmsInNoticeEntity::getId, wmsInNotice.getId())
                .eq(StrUtil.isNotBlank(wmsInNotice.getState()), WmsInNoticeEntity::getState, wmsInNotice.getState())
                .eq(StrUtil.isNotBlank(wmsInNotice.getWarehouseCode()), WmsInNoticeEntity::getWarehouseCode, wmsInNotice.getWarehouseCode())
                .like(StrUtil.isNotBlank(wmsInNotice.getCreateBy()), BaseCore::getCreateBy, wmsInNotice.getCreateBy())
                .eq(WmsInNoticeEntity::getBillType, billType)
                .orderByAsc(WmsInNoticeEntity::getState)
                .orderByDesc(BaseCore::getCreateTime);
        return wrapper;
    }


    public QueryWrapper<WmsInNoticeEntity> getCountQueryWrapper(WmsInNoticeQueryDTO wmsInNotice) {
        QueryWrapper<WmsInNoticeEntity> wrapper = new QueryWrapper<>();
        if (CollUtil.isNotEmpty(wmsInNotice.getCreateTime())) {
            wrapper.lambda().ge(BaseCore::getCreateTime, wmsInNotice.getCreateTime().get(0));
            if (wmsInNotice.getCreateTime().size() > 1) {
                wrapper.lambda().lt(BaseCore::getCreateTime, wmsInNotice.getCreateTime().get(1));
            }
        }
        wrapper.lambda().like(StrUtil.isNotBlank(wmsInNotice.getId()), WmsInNoticeEntity::getId, wmsInNotice.getId())
                .eq(StrUtil.isNotBlank(wmsInNotice.getWarehouseCode()), WmsInNoticeEntity::getWarehouseCode, wmsInNotice.getWarehouseCode())
                .like(StrUtil.isNotBlank(wmsInNotice.getCreateBy()), BaseCore::getCreateBy, wmsInNotice.getCreateBy())
                .eq(WmsInNoticeEntity::getBillType, billType);
        return wrapper;
    }
}