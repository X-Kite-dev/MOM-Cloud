package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity;
import com.kitex.mom.plan.vo.PlnTemporaryDemandDateVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 计划接收mapper
 */
@Mapper
public interface PlnProductDemandMapper extends MomBaseMapper<PlnProductDemandEntity> {

    /**
     * 通过条件查询需求日期
     *
     * @param wrapper 查询条件
     */
    List<PlnTemporaryDemandDateVO> getDemandDate(@Param(Constants.WRAPPER) Wrapper<PlnProductDemandEntity> wrapper);


    int updatePlanDateBatch(@Param("list") List<PlnSchedulingTaskDetailEntity> list);

}
