package com.kitex.mom.wms.controller;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.entity.WmsDeliverGoodsBaseEntity;
import com.kitex.mom.wms.entity.WmsDeliverGoodsReturnEntity;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.enums.DeliverReturnStateEnum;
import com.kitex.mom.wms.service.WmsDeliverGoodsReturnService;
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
import java.util.stream.Collectors;

/**
 * 发货退货表
 *
 * @author kitex
 * @date 2025-01-15 11:50:18
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsDeliverGoodsReturn")
@Tag(description = "wmsDeliverGoodsReturn", name = "发货退货表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsDeliverGoodsReturnController {

    private final WmsDeliverGoodsReturnService wmsDeliverGoodsReturnService;

    /**
     * 发货退货表分页查询
     *
     * @param page                  分页对象
     * @param wmsDeliverGoodsReturn 发货退货表
     * @return
     */

    @Operation(summary = "发货退货表分页查询", description = "分页查询发货退货表数据")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsReturn_view')")
    public R<Page<WmsDeliverGoodsReturnEntity>> getWmsDeliverGoodsReturnPage(@ParameterObject SearchPage page, @ParameterObject WmsDeliverGoodsReturnEntity wmsDeliverGoodsReturn) {
        LambdaQueryWrapper<WmsDeliverGoodsReturnEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsReturn.getDeliverCode()), WmsDeliverGoodsReturnEntity::getDeliverCode, wmsDeliverGoodsReturn.getDeliverCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsReturn.getCustomerCode()), WmsDeliverGoodsReturnEntity::getCustomerCode, wmsDeliverGoodsReturn.getCustomerCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsReturn.getCustomerName()), WmsDeliverGoodsReturnEntity::getCustomerName, wmsDeliverGoodsReturn.getCustomerName());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsReturn.getHandlePersonName()), WmsDeliverGoodsReturnEntity::getHandlePersonName, wmsDeliverGoodsReturn.getHandlePersonName());
        wrapper.eq(StrUtil.isNotBlank(wmsDeliverGoodsReturn.getState()), WmsDeliverGoodsReturnEntity::getState, wmsDeliverGoodsReturn.getState());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(wmsDeliverGoodsReturnService.page(page.toMpPage(), wrapper));
    }


    /**
     * 发货退货表分页 - 统计
     */

    @GetMapping("/getCount")
    @Operation(summary = "发货退货表分页 - 统计", description = "发货退货表分页 - 统计")
    public R<Map<String, Long>> getCount(@ParameterObject WmsDeliverGoodsReturnEntity wmsDeliverGoodsReturn) {
        LambdaQueryWrapper<WmsDeliverGoodsReturnEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsReturn.getDeliverCode()), WmsDeliverGoodsReturnEntity::getDeliverCode, wmsDeliverGoodsReturn.getDeliverCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsReturn.getCustomerCode()), WmsDeliverGoodsReturnEntity::getCustomerCode, wmsDeliverGoodsReturn.getCustomerCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsReturn.getCustomerName()), WmsDeliverGoodsReturnEntity::getCustomerName, wmsDeliverGoodsReturn.getCustomerName());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsReturn.getHandlePersonName()), WmsDeliverGoodsReturnEntity::getHandlePersonName, wmsDeliverGoodsReturn.getHandlePersonName());
        return R.ok(wmsDeliverGoodsReturnService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsDeliverGoodsReturnEntity::getState, Collectors.counting())));
    }


    /**
     * 通过ID查询发货退货表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过ID查询发货退货表", description = "根据ID获取发货退货表详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsReturn_view')")
    public R<WmsDeliverGoodsReturnEntity> getReturnById(@PathVariable("id") Long id) {
        return R.ok(wmsDeliverGoodsReturnService.getReturnById(id));
    }

    /**
     * 通过发货单号查询入库通知单
     */
    @Operation(summary = "通过ID查询发货退货表", description = "根据ID获取发货退货表详情")
    @GetMapping("/getInNotice")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsReturn_view')")
    public R<List<WmsInNoticeDetailEntity>> getInNotice(@RequestParam("deliverCode") String deliverCode) {
        return R.ok(wmsDeliverGoodsReturnService.getInNotice(deliverCode));
    }

    /**
     * 新增发货退货表
     *
     * @param wmsDeliverGoodsReturn 发货退货表
     * @return R
     */
    @Operation(summary = "新增发货退货表", description = "添加新的发货退货记录")
    @SysLog("新增发货退货表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsReturn_add')")
    public R<Boolean> saveReturn(@RequestBody WmsDeliverGoodsReturnEntity wmsDeliverGoodsReturn) {
        wmsDeliverGoodsReturn.setState(DeliverReturnStateEnum.CREATE.getCode());
        return R.ok(wmsDeliverGoodsReturnService.saveReturn(wmsDeliverGoodsReturn));
    }

    /**
     * 修改发货退货表
     *
     * @param wmsDeliverGoodsReturn 发货退货表
     * @return R
     */
    @Operation(summary = "修改发货退货表", description = "修改发货退货表")
    @SysLog("修改发货退货表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsReturn_edit')")
    public R updateReturnById(@RequestBody WmsDeliverGoodsReturnEntity wmsDeliverGoodsReturn) {
        return R.ok(wmsDeliverGoodsReturnService.updateReturnById(wmsDeliverGoodsReturn));
    }

    /**
     * 通过id删除发货退货表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除发货退货表", description = "通过id删除发货退货表")
    @SysLog("通过id删除发货退货表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsReturn_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(wmsDeliverGoodsReturnService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param wmsDeliverGoodsReturn 查询条件
     * @param ids                   导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsReturn_export')")
    public List<WmsDeliverGoodsReturnEntity> export(WmsDeliverGoodsReturnEntity wmsDeliverGoodsReturn, Long[] ids) {
        return wmsDeliverGoodsReturnService.list(Wrappers.lambdaQuery(wmsDeliverGoodsReturn).in(ArrayUtil.isNotEmpty(ids), WmsDeliverGoodsReturnEntity::getId, ids));
    }


    /**
     * 补发
     */
    @Operation(summary = "补发", description = "补发")
    @PostMapping("/reissue")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsReturn_reissue')")
    public R reissue(@RequestBody WmsDeliverGoodsBaseEntity base) {
        return R.ok(wmsDeliverGoodsReturnService.reissue(base));
    }

    /**
     * 通过
     */
    @Operation(summary = "通过", description = "通过")
    @GetMapping("/pass/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsReturn_pass')")
    public R pass(@PathVariable("id") Long id) {
        return R.ok(wmsDeliverGoodsReturnService.pass(id));
    }

    /**
     * 生成补发单号
     */
    @Operation(summary = "生成补发单号", description = "生成补发单号")
    @GetMapping("/createReissueCode")
    public R createReissueCode(@RequestParam("deliverCode") String deliverCode) {
        return R.ok(wmsDeliverGoodsReturnService.createReissueCode(deliverCode));
    }


}