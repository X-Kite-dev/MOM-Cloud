package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPackagingAdoptionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 包装采用永久账仅由派工冻结或显式采用编排写入，不提供通用CRUD入口。 */
@Mapper
public interface MesPackagingAdoptionMapper extends BaseMapper<MesPackagingAdoptionEntity> {
}
