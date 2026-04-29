package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WmsInNoticeDetailMapper extends MomBaseMapper<WmsInNoticeDetailEntity> {

    Page<WmsInNoticeDetailEntity> fetchDetails(Page page, @Param("params") WmsInNoticeDetailQueryDTO params, @Param("ids") Long[] ids);

    List<WmsInNoticeDetailEntity> fetchDetails(@Param("params") WmsInNoticeDetailQueryDTO params, @Param("ids") Long[] ids);

}