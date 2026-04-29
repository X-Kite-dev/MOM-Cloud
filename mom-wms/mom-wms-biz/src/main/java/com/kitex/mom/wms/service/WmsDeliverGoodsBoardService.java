package com.kitex.mom.wms.service;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.wms.dto.SalesBoardLineDto;
import com.kitex.mom.wms.dto.SalesBoardPieDto;
import com.kitex.mom.wms.dto.SalesLedgerLineDto;
import com.kitex.mom.wms.dto.SalesLedgerPageDto;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;

import java.util.List;

public interface WmsDeliverGoodsBoardService {


    /**
     * 销售出货看板分页查询
     */
    Page<WmsDeliverGoodsPlanBaseEntity> salesBoardPage(Page<WmsDeliverGoodsPlanBaseEntity> toMpPage);

    /**
     * 销售出货看板饼图
     */
    List<SalesBoardPieDto> salesBoardPie();

    /**
     * 销售出货看板近30天走势图
     */
    List<SalesBoardLineDto> salesBoardLine();

    /**
     * 销售发货台账分页
     */
    Page<SalesLedgerPageDto> salesLedgerPage(Page<SalesLedgerPageDto> toMpPage, SalesLedgerPageDto query);

    /**
     * 销售发货台账 - 折线图
     */
    List<SalesLedgerLineDto> salesLedgerLine();
}