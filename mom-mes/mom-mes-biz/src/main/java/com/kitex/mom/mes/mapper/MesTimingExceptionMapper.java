package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesTimingExceptionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 独立超时例外主账；原观察不可改，仅受控动作流转，不提供通用CRUD入口。 */
@Mapper
public interface MesTimingExceptionMapper extends MomBaseMapper<MesTimingExceptionEntity> { }

