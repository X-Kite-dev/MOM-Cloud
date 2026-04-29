package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.dto.OmsOrderBaseTrackPageDto;
import com.kitex.mom.oms.entity.OmsOrderBaseTrackEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OmsOrderBaseTrackMapper extends MomBaseMapper<OmsOrderBaseTrackEntity> {

    Page<OmsOrderBaseTrackPageDto> getOmsOrderBaseTrackPage(Page page, @Param("query") OmsOrderBaseTrackPageDto omsOrderBaseTrack);

    List<String> getOmsOrderBaseTrackCount(@Param("query") OmsOrderBaseTrackPageDto omsOrderBaseTrack);
}