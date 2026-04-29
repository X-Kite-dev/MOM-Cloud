package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.SalesLedgerPageDto;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.service.WmsDeliverGoodsBoardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发货看板报表
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsDeliverGoodsBoard")
@Tag(description = "wmsDeliverGoodsBoard", name = "发货看板报表")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsDeliverGoodsBoardController {

    private final WmsDeliverGoodsBoardService wmsDeliverGoodsBoardService;

    /**
     * 销售出货看板分页查询
     */
    @Operation(summary = "销售出货看板分页查询", description = "分页查询销售出货看板数据")
    @GetMapping("/salesBoardPage")
    public R<Page<WmsDeliverGoodsPlanBaseEntity>> salesBoardPage(@ParameterObject SearchPage page) {
        return R.ok(wmsDeliverGoodsBoardService.salesBoardPage(page.toMpPage()));
    }

    /**
     * 销售出货看板饼图
     */
    @Operation(summary = "销售出货看板饼图", description = "获取销售出货看板的饼图数据")
    @GetMapping("/salesBoardPie")
    public R<?> salesBoardPie() {
        return R.ok(wmsDeliverGoodsBoardService.salesBoardPie());
    }


    /**
     * 销售出货看板近30天走势图
     */
    @Operation(summary = "销售出货看板近30天走势图", description = "获取销售出货看板近30天的走势图数据")
    @GetMapping("/salesBoardLine")
    public R<?> salesBoardLine() {
        return R.ok(wmsDeliverGoodsBoardService.salesBoardLine());
    }


    /**
     * 销售发货台账分页
     */
    @Operation(summary = "销售发货台账分页查询", description = "分页查询销售发货台账数据")
    @GetMapping("/salesLedgerPage")
    public R<Page<?>> salesLedgerPage(@ParameterObject SearchPage page, SalesLedgerPageDto query) {
        return R.ok(wmsDeliverGoodsBoardService.salesLedgerPage(page.toMpPage(), query));
    }

    /**
     * 销售发货台账 - 折线图
     */
    @Operation(summary = "销售发货台账折线图", description = "获取销售发货台账的折线图数据")
    @GetMapping("/salesLedgerLine")
    public R<?> salesLedgerLine() {
        return R.ok(wmsDeliverGoodsBoardService.salesLedgerLine());
    }


}