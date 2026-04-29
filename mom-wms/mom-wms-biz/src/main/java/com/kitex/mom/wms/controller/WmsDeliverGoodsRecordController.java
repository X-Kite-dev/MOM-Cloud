package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.oms.enums.YesOrNoStateEnum;
import com.kitex.mom.wms.entity.WmsDeliverGoodsRecordEntity;
import com.kitex.mom.wms.service.WmsDeliverGoodsRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发货记录表
 *
 * @author kitex
 * @date 2025-01-16 09:07:22
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsDeliverGoodsRecord")
@Tag(description = "wmsDeliverGoodsRecord", name = "发货记录表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsDeliverGoodsRecordController {

    private final WmsDeliverGoodsRecordService wmsDeliverGoodsRecordService;


    /**
     * 根据订单号 查询所有 发货记录 时间升序 (只查询正常发货的信息 ， 补发的不查)
     */
    @Operation(summary = "根据订单号查询发货记录", description = "根据订单号查询所有发货记录")
    @GetMapping("/getDeliverRecordListByOrder")
    public R<List<WmsDeliverGoodsRecordEntity>> getDeliverRecordListByOrder(@RequestParam("orderCode") String orderCode) {
        return R.ok(wmsDeliverGoodsRecordService.list(new LambdaQueryWrapper<WmsDeliverGoodsRecordEntity>()
                .eq(WmsDeliverGoodsRecordEntity::getOrderCode, orderCode)
                .eq(WmsDeliverGoodsRecordEntity::getReissueSign, YesOrNoStateEnum.NO.getCode())
                .orderByAsc(WmsDeliverGoodsRecordEntity::getDeliverTime)));
    }

    /**
     * 根据发货计划 查询所有 发货记录 时间升序 (只查询正常发货的信息 ， 补发的不查)
     */
    @Operation(summary = "根据发货计划查询发货记录", description = "根据发货计划查询发货记录")
    @GetMapping("/getDeliverRecordListByPlan")
    public R<List<WmsDeliverGoodsRecordEntity>> getDeliverRecordListByPlan(@RequestParam("planCodeList") List<String> planCodeList) {
        return R.ok(wmsDeliverGoodsRecordService.list(new LambdaQueryWrapper<WmsDeliverGoodsRecordEntity>()
                .in(WmsDeliverGoodsRecordEntity::getDeliverPlanCode, planCodeList)
                .eq(WmsDeliverGoodsRecordEntity::getReissueSign, YesOrNoStateEnum.NO.getCode())
                .orderByAsc(WmsDeliverGoodsRecordEntity::getDeliverTime)));
    }


}