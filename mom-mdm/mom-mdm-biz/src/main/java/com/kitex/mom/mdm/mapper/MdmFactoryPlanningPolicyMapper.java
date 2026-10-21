package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmFactoryPlanningPolicyEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 工厂计划协同策略 Mapper；状态迁移由领域服务控制。 */
@Mapper
public interface MdmFactoryPlanningPolicyMapper extends BaseMapper<MdmFactoryPlanningPolicyEntity> {
    /** 在同一事务内串行化同租户、同工厂的版本号和幂等判断。 */
    @Select("SELECT pg_advisory_xact_lock(hashtext(#{lockKey}))")
    void lock(@Param("lockKey") String lockKey);
}
