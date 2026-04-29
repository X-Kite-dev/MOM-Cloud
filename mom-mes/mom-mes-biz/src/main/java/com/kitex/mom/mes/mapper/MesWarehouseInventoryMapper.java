package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesWarehouseInventoryEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MesWarehouseInventoryMapper extends MomBaseMapper<MesWarehouseInventoryEntity> {

    /**
     * 分页查询
     *
     * @param page                   分页对象
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return
     */
//    Page<WmsInsideInventoryPlanEntity> appPage(Page page,@Param("params") WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan);
}