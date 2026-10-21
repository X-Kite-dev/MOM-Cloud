package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 生产报工
 *
 * @author kitex
 * @since 2025-05-06
 */
@Mapper
public interface MesTaskReportMapper extends MomBaseMapper<MesTaskReportEntity> {

    /** 服务器接受时间独立于客户端设备时间；使用语句时钟，不使用事务开始时间。 */
    @Select("SELECT clock_timestamp()::timestamp(6)")
    java.time.LocalDateTime timingClock();

    /** 依据实际批准路线及下一应过工序解析当次轮次；不取外层审批事务已持有的行锁。 */
    @Select("SELECT \"mom_mes\".mom_timing_route_context_v1(#{tenantId},#{factoryCode},#{taskCode},#{identifier},#{produceTaskCode})::text")
    String timingRouteContext(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                              @Param("taskCode") String taskCode, @Param("identifier") String identifier,
                              @Param("produceTaskCode") String produceTaskCode);

    /** 根据真实路线上下文选择明确来源轮次，选中轮次缺报告时拒绝而不是退回首轮。 */
    @Select("SELECT \"mom_mes\".mom_timing_visit_source_v1(#{tenantId},#{factoryCode},#{taskCode},#{identifier},"
            + "#{produceTaskCode},#{reportMode},#{strategy},CAST(#{context} AS jsonb))::text")
    String timingVisitSource(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                             @Param("taskCode") String taskCode, @Param("identifier") String identifier,
                             @Param("produceTaskCode") String produceTaskCode, @Param("reportMode") String reportMode,
                             @Param("strategy") String strategy, @Param("context") String context);

    /** 读取已完成动态报工的原实例/组/命令/过站/正向操作证明，不读取外码当前新占用。 */
    @Select("SELECT \"mom_mes\".mom_process_timing_binding_source_v1(#{tenantId},#{factoryCode},#{reportId})::text")
    String timingBindingSource(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                               @Param("reportId") Long reportId);

    /** 原号包含逻辑删除历史，缺少原正文的旧报工不能被新命令重新占用。 */
    @Select("SELECT count(*) FROM mes_task_report WHERE tenant_id=#{tenantId} AND request_id=#{requestId}")
    long countHistoricalRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);

    /** 旧内部SN历史包含逻辑删除，不能将其作为新夹具外码而创造双重含义。 */
    @Select("SELECT count(*) FROM mes_task_report WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND transfer_code=#{identifier}")
    long countCanonicalIdentifierHistory(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                         @Param("identifier") String identifier);
}
