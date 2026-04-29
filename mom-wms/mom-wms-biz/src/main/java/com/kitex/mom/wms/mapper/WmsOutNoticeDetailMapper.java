package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WmsOutNoticeDetailMapper extends MomBaseMapper<WmsOutNoticeDetailEntity> {

    /**
     * 分页查询明细
     *
     * @param page   分页参数
     * @param params 查询条件
     * @return
     */
    Page<WmsOutNoticeDetailEntity> fetchDetails(Page page, @Param("params") WmsInNoticeDetailQueryDTO params, @Param("ids") Long[] ids);

    List<WmsOutNoticeDetailEntity> fetchDetails(@Param("params") WmsInNoticeDetailQueryDTO params, @Param("ids") Long[] ids);
}
