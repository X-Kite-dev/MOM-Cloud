package com.kitex.mom.pms.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PmsPlanMapper extends MomBaseMapper<PmsPlanEntity> {

    List<PmsPlanEntity> getPmsPlanList(@Param(Constants.WRAPPER) Wrapper<PmsPlanEntity> wrapper);
}