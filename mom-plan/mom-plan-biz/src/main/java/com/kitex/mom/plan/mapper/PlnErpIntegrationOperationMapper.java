package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnErpIntegrationOperationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * ERP 投递操作履历持久化入口。
 */
@Mapper
public interface PlnErpIntegrationOperationMapper extends MomBaseMapper<PlnErpIntegrationOperationEntity> {
}
