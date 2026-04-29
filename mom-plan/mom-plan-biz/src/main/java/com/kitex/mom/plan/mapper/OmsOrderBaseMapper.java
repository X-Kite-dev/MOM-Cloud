package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.dto.HistoryDeliverOrderDto;
import com.kitex.mom.oms.dto.OmsOrderBasePageDto;
import com.kitex.mom.oms.entity.OmsOrderBaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OmsOrderBaseMapper extends MomBaseMapper<OmsOrderBaseEntity> {


    Page<OmsOrderBasePageDto> getOmsOrderBasePage(Page<OmsOrderBasePageDto> page, @Param("query") OmsOrderBasePageDto omsOrderBase);

    List<OmsOrderBasePageDto> getOmsOrderBaseCount(@Param("query") OmsOrderBasePageDto omsOrderBase);

    /**
     * 根据产品编码 查询 该产品所有历史未交付订单列表
     *
     * @param productCode 产品编码
     */
    List<HistoryDeliverOrderDto> getHistoryDeliverOrderByProductCode(@Param("orderCode") String orderCode, @Param("productCode") String productCode);


    /**
     * 根据产品编码集合 查询 该产品所有历史未交付订单列表
     *
     * @param productCodes 产品编码集合
     */
    List<HistoryDeliverOrderDto> getHistoryDeliverOrderByProductCodes(@Param("orderCode") String orderCode, @Param("productCodes") List<String> productCodes);


}