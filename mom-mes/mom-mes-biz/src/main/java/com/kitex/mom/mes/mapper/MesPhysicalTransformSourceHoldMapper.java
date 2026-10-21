package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalTransformSourceHoldEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** 永久占用原事实与必要状态写；原号/本人验证由领域服务完成。 */
@Mapper
public interface MesPhysicalTransformSourceHoldMapper extends BaseMapper<MesPhysicalTransformSourceHoldEntity> {
    /** 一条SQL同快照返回主状态及完整身份计数；不经逻辑删除漏掉原历史。 */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT h.*, (SELECT count(*) FROM mes_physical_transform_source_claim c WHERE c.hold_id=h.id "
            + "AND c.tenant_id=h.tenant_id AND c.factory_code=h.factory_code) AS identity_count, "
            + "(SELECT count(*) FROM mes_physical_transform_source_claim c WHERE c.hold_id=h.id "
            + "AND c.tenant_id=h.tenant_id AND c.factory_code=h.factory_code AND c.del_flag='0' "
            + "AND c.status=CASE h.status WHEN 'SOURCE_HELD' THEN 'HELD' WHEN 'CANCELLED' THEN 'RELEASED' "
            + "WHEN 'APPLIED' THEN 'CONSUMED' ELSE '' END) "
            + "AS matching_identity_count FROM mes_physical_transform_source_hold h "
            + "WHERE h.tenant_id=#{tenantId} AND h.command_id=#{commandId}")
    MesPhysicalTransformSourceHoldEntity permanent(@Param("tenantId") Long tenantId, @Param("commandId") Long commandId);

    /** 已持永久原号后锁精确占用主账；不存在或状态变化均拒绝执行。 */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT h.*, (SELECT count(*) FROM mes_physical_transform_source_claim c WHERE c.hold_id=h.id "
            + "AND c.tenant_id=h.tenant_id AND c.factory_code=h.factory_code) AS identity_count, "
            + "(SELECT count(*) FROM mes_physical_transform_source_claim c WHERE c.hold_id=h.id "
            + "AND c.tenant_id=h.tenant_id AND c.factory_code=h.factory_code AND c.del_flag='0' "
            + "AND c.status=CASE h.status WHEN 'SOURCE_HELD' THEN 'HELD' WHEN 'CANCELLED' THEN 'RELEASED' "
            + "WHEN 'APPLIED' THEN 'CONSUMED' ELSE '' END) AS matching_identity_count "
            + "FROM mes_physical_transform_source_hold h WHERE h.tenant_id=#{tenantId} AND h.command_id=#{commandId} FOR UPDATE NOWAIT")
    MesPhysicalTransformSourceHoldEntity locked(@Param("tenantId") Long tenantId, @Param("commandId") Long commandId);

    /** 只查目录，先区分未部署与真正NONE；恢复不因守卫暂缺而遗失可读原账。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT to_regclass('\"mom_mes\".mes_physical_transform_source_hold') IS NOT NULL "
            + "AND to_regclass('\"mom_mes\".mes_physical_transform_source_claim') IS NOT NULL")
    Boolean available();

    /** 防新Java先于SQL部署时调用不存在函数，不用本地marker替代实际目录。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT to_regprocedure('\"mom_mes\".mom_physical_source_hold_ready()') IS NOT NULL")
    Boolean readinessAvailable();

    /** 仅来源生命周期目录齐备才允许真实占用，不能以本地布尔配置放行。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_physical_source_hold_ready()")
    Boolean ready();

    /** 原本人、原SHA、原活动状态CAS；取消与epoch/身份提交证明由数据库独立保护。 */
    @Update("UPDATE mes_physical_transform_source_hold SET status='CANCELLED',cancelled_time=#{time},cancel_reason=#{reason} "
            + "WHERE id=#{id} AND tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND actor_id=#{actorId} "
            + "AND actor_code=#{actorCode} AND frozen_fingerprint=#{fingerprint} AND status='SOURCE_HELD' AND del_flag='0'")
    int cancel(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
               @Param("actorId") Long actorId, @Param("actorCode") String actorCode, @Param("fingerprint") String fingerprint,
               @Param("time") LocalDateTime time, @Param("reason") String reason);

    /** 执行主账和全部子事实已写后，同事务把活动占用收口为不可重开的APPLIED。 */
    @Update("UPDATE mes_physical_transform_source_hold SET status='APPLIED' WHERE id=#{id} AND tenant_id=#{tenantId} "
            + "AND factory_code=#{factoryCode} AND actor_id=#{actorId} AND actor_code=#{actorCode} "
            + "AND frozen_fingerprint=#{fingerprint} AND status='SOURCE_HELD' AND del_flag='0'")
    int apply(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
              @Param("actorId") Long actorId, @Param("actorCode") String actorCode,
              @Param("fingerprint") String fingerprint);
}
