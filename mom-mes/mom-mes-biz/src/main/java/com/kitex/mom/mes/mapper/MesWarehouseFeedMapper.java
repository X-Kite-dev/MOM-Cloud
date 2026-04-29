package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.dto.MesWarehouseFeedLeftDTO;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MesWarehouseFeedMapper extends MomBaseMapper<MesWarehouseFeedEntity> {

    List<MesWarehouseFeedLeftDTO> queryTaskFeedList(@Param("query") MesWarehouseFeedLeftDTO query);

    IPage<MesWarehouseFeedLeftDTO> queryLeftPage(Page<MesWarehouseFeedLeftDTO> pageParam, @Param("query") MesWarehouseFeedLeftDTO query);
}