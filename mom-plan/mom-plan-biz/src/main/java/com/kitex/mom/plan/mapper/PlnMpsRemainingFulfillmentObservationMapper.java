package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnMpsRemainingFulfillmentObservationEntity;
import org.apache.ibatis.annotations.Mapper;

/** 剩余承诺不可变兑现观察的组织隔离数据访问，不暴露通用写入口。 */
@Mapper
public interface PlnMpsRemainingFulfillmentObservationMapper extends MomBaseMapper<PlnMpsRemainingFulfillmentObservationEntity> {
}

