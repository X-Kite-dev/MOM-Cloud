package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.dto.WmsInsideInventoryPlanQueryDTO;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WmsInsideInventoryPlanMapper extends MomBaseMapper<WmsInsideInventoryPlanEntity> {

    /**
     * 分页查询
     *
     * @param page                   分页对象
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return
     */
    Page<WmsInsideInventoryPlanEntity> appPage(Page page, @Param("params") WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan);
}