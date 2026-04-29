package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.dto.OmsOrderBaseReviewPageDto;
import com.kitex.mom.oms.entity.OmsOrderBaseReviewEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OmsOrderBaseReviewMapper extends MomBaseMapper<OmsOrderBaseReviewEntity> {


    Page<OmsOrderBaseReviewPageDto> getOmsOrderBaseReviewPage(Page<OmsOrderBaseReviewPageDto> page,
                                                              @Param("query") OmsOrderBaseReviewPageDto omsOrderBaseReview);

    List<OmsOrderBaseReviewPageDto> getOmsOrderBaseReviewCount(@Param("query") OmsOrderBaseReviewPageDto omsOrderBaseReview);
}