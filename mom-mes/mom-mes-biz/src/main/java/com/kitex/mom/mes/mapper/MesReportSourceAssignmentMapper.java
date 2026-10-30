package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesReportSourceAssignmentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 不可变核定版本的持久接口，组织条件由领域服务显式限定。
 */
@Mapper
public interface MesReportSourceAssignmentMapper extends MomBaseMapper<MesReportSourceAssignmentEntity> {
}
