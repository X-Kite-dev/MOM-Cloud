package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesChangePointEffectRetestEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 变化点改善复测事实账，不提供通用业务修改或删除入口。
 */
@Mapper
public interface MesChangePointEffectRetestMapper extends MomBaseMapper<MesChangePointEffectRetestEntity> {
}
