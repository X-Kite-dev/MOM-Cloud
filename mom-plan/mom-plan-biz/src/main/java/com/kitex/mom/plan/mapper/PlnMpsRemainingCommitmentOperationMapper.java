package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentOperationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 剩余生产承诺版本账的组织隔离数据访问，不暴露通用写入口。
 */
@Mapper
public interface PlnMpsRemainingCommitmentOperationMapper extends MomBaseMapper<PlnMpsRemainingCommitmentOperationEntity> {
}

