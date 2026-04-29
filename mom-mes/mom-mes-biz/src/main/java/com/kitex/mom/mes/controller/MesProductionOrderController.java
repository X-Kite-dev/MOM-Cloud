package com.kitex.mom.mes.controller;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.dto.MesProductionOrderDTO;
import com.kitex.mom.mes.entity.MesProductionOrderEntity;
import com.kitex.mom.mes.service.MesProductionOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产订单
 * 1. 可通过生产订单排期后产生生产工单
 * 2. 有计划模块下发产品生产订单
 * 3. 后期增加需求计划功能，排程产生生产订单
 */
@Tag(description = "MesProductionOrder", name = "生产订单")
@RestController
@RequestMapping("/MesProductionOrder")
@RequiredArgsConstructor
public class MesProductionOrderController {

    private final MesProductionOrderService mesProductionOrderService;


    /**
     * 订单分页查询
     */
    @Operation(summary = "订单分页查询", description = "订单分页查询")
    @GetMapping("/page")
    public R<IPage<MesProductionOrderEntity>> page(Page<MesProductionOrderEntity> pageParam, MesProductionOrderDTO query) {

        QueryWrapper<MesProductionOrderEntity> objectQueryWrapper = QueryGenerator.initQueryWrapper(query);

        return R.ok(mesProductionOrderService.page(pageParam, objectQueryWrapper));
    }

    /**
     * 查看订单详情
     */
    @Operation(summary = "查看订单详情", description = "查看订单详情")
    @GetMapping("/findById")
    public R<MesProductionOrderEntity> findById(Long id) {
        return R.ok(mesProductionOrderService.getById(id));
    }


    /**
     * 工单排期
     */
    @Operation(summary = "工单排期", description = "工单排期")
    @GetMapping("/schedule")
    public R<Boolean> schedule(MesProductionOrderDTO dto) {
        return R.ok(mesProductionOrderService.schedule(dto));
    }


}
