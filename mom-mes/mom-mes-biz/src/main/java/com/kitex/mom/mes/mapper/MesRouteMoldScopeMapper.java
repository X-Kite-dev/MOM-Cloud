package com.kitex.mom.mes.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 节点模具工程事务的真实组织和父路线锁入口。
 *
 * <p>当前kitex_mom按schema分业务；函数SECURITY INVOKER、显式实际scope，且不返回任意表。
 * 写入口在同一MES事务内锁MDM父路线并复核节点，不能用Feign读调用冒充持锁。</p>
 */
@Mapper
public interface MesRouteMoldScopeMapper {

    /**
     * id或路线节点二选一；仅旧编码入口可按唯一主工序桥接。
     */
    @Select("SELECT \"mom_mdm\".mom_route_node_configuration_v1(#{tenant},#{factory},#{id},"
            + "#{route},#{node},#{process},#{lock})::text")
    String context(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id,
                   @Param("route") String route, @Param("node") String node,
                   @Param("process") String process, @Param("lock") boolean lock);
}
