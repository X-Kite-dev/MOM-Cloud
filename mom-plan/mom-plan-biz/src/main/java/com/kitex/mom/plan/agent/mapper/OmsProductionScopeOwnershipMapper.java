package com.kitex.mom.plan.agent.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 旧OMS交付行没有tenant_id，必须先核验订单父级所有权且无同厂同号跨租户冲突。
 * 仅返回当前租户的安全匹配计数，不返回其他租户事实；SQL显式携带实名租户边界。
 */
@Mapper
public interface OmsProductionScopeOwnershipMapper {

    /**
     * 未唯一归属当前租户时返回0；不得仅凭工厂/订单号读取无租户列的旧交付表。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT count(*) FROM "mom_plan".oms_order_base b
            WHERE b.tenant_id = #{tenantId} AND b.factory_code = #{factoryCode}
              AND b.order_code = #{orderCode} AND b.del_flag = '0'
              AND NOT EXISTS (
                SELECT 1 FROM "mom_plan".oms_order_base other
                WHERE other.factory_code = b.factory_code AND other.order_code = b.order_code
                  AND other.del_flag = '0' AND other.tenant_id IS DISTINCT FROM b.tenant_id
              )
            """)
    long countSafeOwner(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                        @Param("orderCode") String orderCode);
}
