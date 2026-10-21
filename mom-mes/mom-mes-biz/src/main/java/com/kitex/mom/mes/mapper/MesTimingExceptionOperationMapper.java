package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesTimingExceptionOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/** 时间例外永久操作账；同号同文恢复，不修改或删除历史审批。 */
@Mapper
public interface MesTimingExceptionOperationMapper extends MomBaseMapper<MesTimingExceptionOperationEntity> {
    /** 与写入共用原动作锁，不能在正在提交时把未见记录误认为未受理。 */
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))) locked")
    Integer lockRequest(@Param("key") String key);
}
