package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmRouteEngineeringCommandEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工程联合发布永久命令持久层。
 */
@Mapper
public interface MdmRouteEngineeringCommandMapper extends BaseMapper<MdmRouteEngineeringCommandEntity> {
    /**
     * 同组织永久请求事务锁，跨路线并发也必须先串行再重读。
     */
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{scopeKey}, 0))")
    Integer lockRequest(@Param("scopeKey") String scopeKey);
}
