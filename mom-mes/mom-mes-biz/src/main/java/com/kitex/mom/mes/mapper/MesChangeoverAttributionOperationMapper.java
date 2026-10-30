package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesChangeoverAttributionOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 换型归属永久命令及原结果；仅追加，不以浏览器重试覆盖历史的持久映射，不暴露通用修改接口。
 */
@Mapper
public interface MesChangeoverAttributionOperationMapper extends BaseMapper<MesChangeoverAttributionOperationEntity> {
    /**
     * 事务级非等待锁；请求/源域各自命名，避免与旧业务锁互等。
     */
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(#{key}, 0))")
    boolean tryLock(@Param("key") String key);
}

