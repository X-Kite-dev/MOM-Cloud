package com.kitex.mom.wms.mapper;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsContainerUseHistoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WmsContainerUseHistoryMapper extends MomBaseMapper<WmsContainerUseHistoryEntity> {

    /**
     * 分页查询
     */
    Page<WmsContainerUseHistoryEntity> fetchHistories(Page page, @Param("params") WmsContainerUseHistoryEntity params);
}