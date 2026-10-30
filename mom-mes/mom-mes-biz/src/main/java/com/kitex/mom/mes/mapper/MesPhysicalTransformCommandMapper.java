package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalTransformCommandEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 永久原号查询含所有历史，不经@TableLogic漏掉封存或归档原号。
 */
@Mapper
public interface MesPhysicalTransformCommandMapper extends BaseMapper<MesPhysicalTransformCommandEntity> {
    /**
     * 固定旧V1字面域JSON原号锁，所有版本共用；未来执行先取它，再取真实工单和行锁。
     */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key},0))")
    Integer lockRequest(@Param("key") String key);

    /**
     * 永久号不分工厂：仅此查询免工厂改写，保留显式租户及服务实际工厂/本人核验。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT * FROM mes_physical_transform_command WHERE tenant_id=#{tenantId} AND request_id=#{requestId}")
    MesPhysicalTransformCommandEntity permanent(@Param("tenantId") Long tenantId,
                                                @Param("requestId") String requestId);

    /**
     * 已持原号锁查原行；免工厂改写避免切厂误报不存在，服务拒绝非本人/非原工厂。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT * FROM mes_physical_transform_command WHERE tenant_id=#{tenantId} AND request_id=#{requestId} FOR UPDATE NOWAIT")
    MesPhysicalTransformCommandEntity locked(@Param("tenantId") Long tenantId,
                                             @Param("requestId") String requestId);

    /**
     * 只对原REGISTERED本人封存，不覆盖原文/首次时间；必要写必须恰好一行。
     */
    @Update("UPDATE mes_physical_transform_command SET status='SEALED',sealed_time=#{sealedTime},seal_reason=#{reason} "
            + "WHERE id=#{id} AND tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND actor_id=#{actorId} "
            + "AND actor_code=#{actorCode} AND request_fingerprint=#{fingerprint} AND status='REGISTERED' AND del_flag='0'")
    int seal(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
             @Param("actorId") Long actorId, @Param("actorCode") String actorCode,
             @Param("fingerprint") String fingerprint, @Param("sealedTime") java.time.LocalDateTime sealedTime,
             @Param("reason") String reason);

    /**
     * 正式执行同事务终结原命令；数据库要求存在同命令APPLIED主账和已消费来源占用。
     */
    @Update("UPDATE mes_physical_transform_command SET status='APPLIED' WHERE id=#{id} AND tenant_id=#{tenantId} "
            + "AND factory_code=#{factoryCode} AND actor_id=#{actorId} AND actor_code=#{actorCode} "
            + "AND request_fingerprint=#{fingerprint} AND status='REGISTERED' AND del_flag='0'")
    int apply(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
              @Param("actorId") Long actorId, @Param("actorCode") String actorCode,
              @Param("fingerprint") String fingerprint);
}
