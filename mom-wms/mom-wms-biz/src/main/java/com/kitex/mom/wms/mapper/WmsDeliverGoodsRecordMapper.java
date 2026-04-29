package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.dto.SalesBoardLineDto;
import com.kitex.mom.wms.dto.SalesBoardPieDto;
import com.kitex.mom.wms.dto.SalesLedgerPageDto;
import com.kitex.mom.wms.entity.WmsDeliverGoodsRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface WmsDeliverGoodsRecordMapper extends MomBaseMapper<WmsDeliverGoodsRecordEntity> {

    /**
     * 销售出货看板饼图  产品发货TOP10
     */
    List<SalesBoardPieDto> salesBoardPie(@Param("startTime") LocalDate startTime, @Param("endTime") LocalDate endTime);

    /**
     * 销售出货看板近30天走势图 - 时间范围内 每天的出货量
     */
    List<SalesBoardLineDto> salesBoardLine(@Param("startTime") LocalDate startTime, @Param("endTime") LocalDate endTime);

    /**
     * 销售发货台账分页
     */
    Page<SalesLedgerPageDto> salesLedgerPage(Page<SalesLedgerPageDto> toMpPage, @Param("query") SalesLedgerPageDto query);
}