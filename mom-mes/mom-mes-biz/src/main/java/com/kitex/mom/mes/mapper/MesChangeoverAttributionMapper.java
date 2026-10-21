package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesChangeoverAttributionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 已完成换型工序归属案件；原证据不覆盖，错误归属经独立撤销案件解除的持久映射，不暴露通用修改接口。 */
@Mapper
public interface MesChangeoverAttributionMapper extends BaseMapper<MesChangeoverAttributionEntity> {
}

