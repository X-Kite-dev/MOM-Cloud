package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesMaterialReturnOperationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 线边余料受控退库不可变操作履历。
 */
@Mapper
public interface MesMaterialReturnOperationMapper extends MomBaseMapper<MesMaterialReturnOperationEntity> {
}
