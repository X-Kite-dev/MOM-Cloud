package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesProductionMaterialRestoreEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 逐feed恢复原分配；实际负向mutation由数据库绑定，不由客户端指定。
 */
@Mapper
public interface MesProductionMaterialRestoreMapper extends MomBaseMapper<MesProductionMaterialRestoreEntity> {
}
