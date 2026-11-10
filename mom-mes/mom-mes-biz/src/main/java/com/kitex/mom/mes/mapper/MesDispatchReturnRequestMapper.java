package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesDispatchReturnRequestEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesDispatchReturnRequestMapper extends MomBaseMapper<MesDispatchReturnRequestEntity> {
    /** 串行处理同一退派命令或派工，锁随外层业务事务释放。 */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_DISPATCH_RETURN:', #{key}), 0))")
    Integer lockKey(@Param("key") String key);
}
