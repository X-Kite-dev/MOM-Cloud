package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalPositionSurveyEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** 母件真实位置观察原账；确认不是质量放行、来源占用或转换执行；永久查询不以逻辑删除隐藏原历史。 */
@Mapper
public interface MesPhysicalPositionSurveyMapper extends BaseMapper<MesPhysicalPositionSurveyEntity> {
    /** 永久原号采用独立版本域；try锁不等待另一端或旧未知事务。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(#{key},0))")
    Boolean tryRequest(@Param("key") String key);

    /** 不经逻辑删除或工厂改写隐藏同租户原号，服务核实际原工厂后才交付原文。 */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT s.*, jsonb_build_object('sources',COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.stock_id) "
            + "FROM mes_physical_position_source c WHERE c.survey_id=s.id AND c.tenant_id=s.tenant_id),'[]'::jsonb),"
            + "'positions',COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.position_code) FROM mes_physical_position_slot c "
            + "WHERE c.survey_id=s.id AND c.tenant_id=s.tenant_id),'[]'::jsonb),"
            + "'allocations',COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.position_code,c.stock_id) "
            + "FROM mes_physical_position_allocation c WHERE c.survey_id=s.id AND c.tenant_id=s.tenant_id),'[]'::jsonb),"
            + "'operations',COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.revision) FROM mes_physical_position_operation c "
            + "WHERE c.survey_id=s.id AND c.tenant_id=s.tenant_id),'[]'::jsonb))::text AS ledger_snapshot "
            + "FROM mes_physical_position_survey s WHERE s.tenant_id=#{tenantId} AND s.request_id=#{requestId}")
    MesPhysicalPositionSurveyEntity permanent(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);

    /** 已取原号和实际source scope保护后NOWAIT锁原主账，禁止先持stock再等待其他父行。 */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT * FROM mes_physical_position_survey WHERE tenant_id=#{tenantId} AND request_id=#{requestId} FOR UPDATE NOWAIT")
    MesPhysicalPositionSurveyEntity locked(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);

    /** 正式转换按精确主键锁已确认原账，不按母码或最近时间猜位置记录。 */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT s.*, jsonb_build_object('sources',COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.stock_id) "
            + "FROM mes_physical_position_source c WHERE c.survey_id=s.id AND c.tenant_id=s.tenant_id),'[]'::jsonb),"
            + "'positions',COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.position_code) FROM mes_physical_position_slot c "
            + "WHERE c.survey_id=s.id AND c.tenant_id=s.tenant_id),'[]'::jsonb),"
            + "'allocations',COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.position_code,c.stock_id) "
            + "FROM mes_physical_position_allocation c WHERE c.survey_id=s.id AND c.tenant_id=s.tenant_id),'[]'::jsonb),"
            + "'operations',COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.revision) FROM mes_physical_position_operation c "
            + "WHERE c.survey_id=s.id AND c.tenant_id=s.tenant_id),'[]'::jsonb))::text AS ledger_snapshot "
            + "FROM mes_physical_position_survey s WHERE s.id=#{surveyId} AND s.tenant_id=#{tenantId} "
            + "AND s.factory_code=#{factoryCode} FOR UPDATE NOWAIT")
    MesPhysicalPositionSurveyEntity lockedById(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                               @Param("surveyId") Long surveyId);

    /** 只有原待确认状态和全文SHA可迁移，完整事件由数据库同事务证明。 */
    @Update("UPDATE mes_physical_position_survey SET status=#{status},revision=2,last_operation_id=#{operationId},decided_time=#{time} "
            + "WHERE id=#{id} AND tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND status='OBSERVED' "
            + "AND revision=1 AND survey_fingerprint=#{surveyFingerprint} AND evidence_fingerprint=#{evidenceFingerprint} AND del_flag='0'")
    int decide(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
               @Param("surveyFingerprint") String surveyFingerprint, @Param("evidenceFingerprint") String evidenceFingerprint,
               @Param("status") String status, @Param("operationId") Long operationId, @Param("time") LocalDateTime time);

    /** 未部署明确UNAVAILABLE，不把不存在表解释为没有原观察。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT to_regclass('\"mom_mes\".mes_physical_position_survey') IS NOT NULL "
            + "AND to_regclass('\"mom_mes\".mes_physical_position_source') IS NOT NULL "
            + "AND to_regclass('\"mom_mes\".mes_physical_position_slot') IS NOT NULL "
            + "AND to_regclass('\"mom_mes\".mes_physical_position_allocation') IS NOT NULL "
            + "AND to_regclass('\"mom_mes\".mes_physical_position_operation') IS NOT NULL")
    Boolean available();

    /** 批量登记必须部署255即时集合保护；旧原账恢复仍不依赖新的写就绪门。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT to_regprocedure('\"mom_mes\".mom_physical_position_ready()') IS NOT NULL "
            + "AND to_regprocedure('\"mom_mes\".mom_physical_position_statement_ready()') IS NOT NULL")
    Boolean readinessAvailable();

    /** 完整位置业务保护齐备才允许写，不影响独立原事实恢复。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_physical_position_ready()")
    Boolean ready();
}
