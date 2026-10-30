package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesProductionMaterialAllocationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 实际耗用分配原账；不提供改账或冲销旁路。
 */
@Mapper
public interface MesProductionMaterialAllocationMapper extends MomBaseMapper<MesProductionMaterialAllocationEntity> {
}
