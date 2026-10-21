package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnApsCaseOperationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 智能 APS 案件不可变操作履历持久化入口。
 */
@Mapper
public interface PlnApsCaseOperationMapper extends MomBaseMapper<PlnApsCaseOperationEntity> {
}
