package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmFactoryPlanningPolicyEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工厂计划协同策略 Mapper；状态迁移由领域服务控制。
 */
@Mapper
public interface MdmFactoryPlanningPolicyMapper extends BaseMapper<MdmFactoryPlanningPolicyEntity> {
    /**
     * 不过滤软删除记录，防止历史发布版本被误判为从未启用。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT count(*) FROM mom_mdm.mdm_factory_planning_policy
            WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode}
              AND (status <> 'DRAFT' OR status IS NULL OR published_by IS NOT NULL
                   OR published_time IS NOT NULL OR publish_request_id IS NOT NULL
                   OR publish_command_fingerprint IS NOT NULL OR impact_confirmation_no IS NOT NULL
                   OR submitted_by IS NOT NULL OR submitted_time IS NOT NULL
                   OR initial_activation_snapshot IS NOT NULL)
            """)
    long countEverPublished(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode);

    /**
     * 在同一事务内串行化同租户、同工厂的版本号和幂等判断。
     */
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtext(#{lockKey}))")
    Integer lock(@Param("lockKey") String lockKey);
}
