package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesProductionNgCancellationEntity;
import org.apache.ibatis.annotations.Mapper;

/** 永久作废账由采集IService协调，禁止普通CRUD修改或删除。 */
@Mapper
public interface MesProductionNgCancellationMapper extends MomBaseMapper<MesProductionNgCancellationEntity> {
}
