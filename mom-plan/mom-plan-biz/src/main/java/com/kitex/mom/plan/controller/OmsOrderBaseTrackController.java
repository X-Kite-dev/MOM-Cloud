package com.kitex.mom.plan.controller;


import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.oms.dto.OmsOrderBaseTrackPageDto;
import com.kitex.mom.oms.dto.OmsOrderOverviewDto;
import com.kitex.mom.oms.dto.OmsUpdateOrderDeliverDto;
import com.kitex.mom.oms.entity.OmsOrderBaseTrackEntity;
import com.kitex.mom.oms.vo.OmsLifeVO;
import com.kitex.mom.oms.vo.OmsOrderDetailsVO;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 订单跟踪表
 *
 * @author kitex
 * @date 2025-01-07 14:36:05
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/omsOrderBaseTrack")
@Tag(description = "omsOrderBaseTrack", name = "订单跟踪表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class OmsOrderBaseTrackController {

    private final OmsOrderBaseTrackService omsOrderBaseTrackService;

    /**
     * 订单跟踪表分页查询
     */

    @Operation(summary = "订单跟踪表分页查询", description = "订单跟踪表分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<Page<OmsOrderBaseTrackPageDto>> getOmsOrderBaseTrackPage(@ParameterObject SearchPage page,
                                                                      @ParameterObject OmsOrderBaseTrackPageDto omsOrderBaseTrack) {
        return R.ok(omsOrderBaseTrackService.getOmsOrderBaseTrackPage(page.toMpPage(), omsOrderBaseTrack));
    }

    /**
     * 订单总览分页查询
     */

    @Operation(summary = "订单总览分页查询", description = "订单总览分页查询")
    @GetMapping("/overviewPage")
    public R<Page<OmsOrderOverviewDto>> getOverviewPage(@ParameterObject SearchPage page,
                                                        @ParameterObject OmsOrderOverviewDto omsOrderOverviewDto) {
        return R.ok(omsOrderBaseTrackService.getOverviewPage(page.toMpPage(), omsOrderOverviewDto));
    }

    /**
     * 订单总览日期列表
     */

    @Operation(summary = "订单总览日期列表", description = "订单总览日期列表")
    @GetMapping("/overviewList")
    public R<List<Map<String, Object>>> getOverviewList(@ParameterObject String[] productCodes, @ParameterObject LocalDate date,
                                                        @ParameterObject Long factoryCode) {
        return R.ok(omsOrderBaseTrackService.getOverviewList(productCodes, date));
    }

    /**
     * 订单总览日期详情
     */
    @Operation(summary = "订单总览日期列表", description = "订单总览日期列表")
    @GetMapping("/overviewDetails")
    public R<List<OmsOrderDetailsVO>> getOverviewDetails(@ParameterObject String productCode, @ParameterObject LocalDate date) {
        return R.ok(omsOrderBaseTrackService.getOverviewDetails(productCode, date));
    }

    /**
     * 查询各订单跟踪状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各订单跟踪状态的数量", description = "查询各订单跟踪状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject OmsOrderBaseTrackPageDto omsOrderBaseTrack) {
        return R.ok(omsOrderBaseTrackService.getCount(omsOrderBaseTrack));
    }

    /**
     * 通过id查询订单跟踪表
     *
     * @param id id
     */
    @Operation(summary = "通过id查询订单跟踪表", description = "通过id查询订单跟踪表")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<OmsOrderBaseTrackEntity> getOrderTrackById(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseTrackService.getOrderTrackById(id));
    }

    /**
     * 通过id查询订单跟踪表
     *
     * @param id id -->明细id
     */
    @Operation(summary = "通过id查询订单跟踪表(生产、发货)-明细id", description = "通过id查询订单跟踪表(生产、发货)-明细id")
    @GetMapping("/details/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<OmsOrderBaseTrackEntity> getOrderTrackDetailsById(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseTrackService.getOrderTrackDetailsById(id));
    }


    /**
     * 根据id 和 日期 更新日历信息
     */
    @Operation(summary = "根据id 和 日期 更新日历信息", description = "根据id 和 日期 更新日历信息")
    @GetMapping("/getCalendarMsg")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<OmsOrderBaseTrackEntity> getCalendarMsg(@RequestParam("id") Long id,
                                                     @RequestParam("date") String date) {
        return R.ok(omsOrderBaseTrackService.getCalendarMsg(id, date));
    }

    /**
     * 导出excel 表格
     *
     * @param omsOrderBaseTrack 查询条件
     * @param ids               导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_export')")
    public List<OmsOrderBaseTrackEntity> export(OmsOrderBaseTrackEntity omsOrderBaseTrack, Long[] ids) {
        return omsOrderBaseTrackService.list(Wrappers.lambdaQuery(omsOrderBaseTrack).in(ArrayUtil.isNotEmpty(ids), OmsOrderBaseTrackEntity::getId, ids));
    }

    /**
     * 订单变更
     */
    @Operation(summary = "订单变更", description = "订单变更")
    @SysLog("订单变更")
    @PostMapping("/change")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_review')")
    public R<Boolean> change(@RequestBody OmsOrderBaseTrackEntity omsOrderBaseTrack) {
        return R.ok(omsOrderBaseTrackService.change(omsOrderBaseTrack));
    }


    /**
     * 订单跟踪下发
     */
    @Operation(summary = "订单跟踪下发", description = "订单跟踪下发")
    @SysLog("订单跟踪下发")
    @GetMapping("/send/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_send')")
    public R<Boolean> send(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseTrackService.send(id));
    }

    /**
     * 订单跟踪下发-计划
     */
    @Operation(summary = "订单跟踪下发-计划", description = "订单跟踪下发-计划")
    @GetMapping("/send/plan/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_send_plan')")
    public R<Boolean> sendPlan(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseTrackService.sendPlan(id));
    }

    /**
     * 订单跟踪下发-发货
     */
    @Operation(summary = "订单跟踪下发-发货", description = "订单跟踪下发-发货")
    @GetMapping("/send/deliver/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_send_deliver')")
    public R<Boolean> sendDeliver(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseTrackService.sendDeliver(id));
    }

    /**
     * 订单跟踪 - 撤回
     */
    @Operation(summary = "订单跟踪 - 撤回", description = "订单跟踪 - 撤回")
    @GetMapping("/withdraw/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_send_withdraw')")
    public R<Boolean> withdraw(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseTrackService.withdraw(id));
    }

    /**
     * 更新订单发货信息
     */
    @Operation(summary = "更新订单发货信息", description = "更新订单发货信息")
    @PostMapping("/updateOrderDeliver")
    public R<Boolean> updateOrderDeliver(@RequestBody List<OmsUpdateOrderDeliverDto> deliverDtoList) {
        return R.ok(omsOrderBaseTrackService.updateOrderDeliver(deliverDtoList));
    }

    /**
     * 订单生命周期
     *
     * @param id id -->明细id
     */
    @Operation(summary = "订单生命周期", description = "订单生命周期")
    @GetMapping("/life/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<OmsLifeVO> lifePlan(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseTrackService.lifePlan(id));
    }

}