package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.oms.entity.OmsOrderDetailsLogEntity;
import com.kitex.mom.plan.service.OmsOrderDetailsLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单交付信息-操作日志表
 *
 * @author kitex
 * @date 2025-04-18 17:35:48
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/omsOrderDetailsLog")
@Tag(description = "omsOrderDetailsLog", name = "订单交付信息-操作日志表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class OmsOrderDetailsLogController {

    private final OmsOrderDetailsLogService omsOrderDetailsLogService;


    /**
     * 通过订单号查询订单交付信息-操作日志表
     *
     * @param orderCode 订单号
     * @return R
     */
    @Operation(summary = "通过订单号查询订单交付信息-操作日志表", description = "通过订单号查询订单交付信息-操作日志表")
    @GetMapping("/getByOrderCode/{orderCode}")
    public R<List<OmsOrderDetailsLogEntity>> getByOrderCode(@PathVariable("orderCode") String orderCode) {
        return R.ok(omsOrderDetailsLogService.list(new LambdaQueryWrapper<OmsOrderDetailsLogEntity>()
                .eq(OmsOrderDetailsLogEntity::getOrderCode, orderCode)));
    }

    /**
     * 通过订单交付信息表id查询订单交付信息-操作日志表
     *
     * @param detailId 订单交付信息表id
     * @return R
     */
    @Operation(summary = "通过订单交付信息表id查询订单交付信息-操作日志表", description = "通过订单交付信息表id查询订单交付信息-操作日志表")
    @GetMapping("/getByDetailId/{detailId}")
    public R<List<OmsOrderDetailsLogEntity>> getByDetailId(@PathVariable("detailId") Long detailId) {
        return R.ok(omsOrderDetailsLogService.list(new LambdaQueryWrapper<OmsOrderDetailsLogEntity>()
                .eq(OmsOrderDetailsLogEntity::getOrderDetailId, detailId)));
    }

}