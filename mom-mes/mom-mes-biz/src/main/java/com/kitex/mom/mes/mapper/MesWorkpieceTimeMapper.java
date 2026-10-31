package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.MesWorkpieceTimeIntervalEntity;
import com.kitex.mom.mes.entity.MesWorkpieceTimeOperationEntity;
import com.kitex.mom.mes.entity.MesWorkpieceTimeSessionEntity;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SQL逐项带认证租户和工厂；原号锁必须先于原回执查询，避免会话缓存空结果。
 */
@Mapper
public interface MesWorkpieceTimeMapper {

    @Select("SELECT mom_mes.mom_workpiece_time_ready()")
    Boolean ready();

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_WORKPIECE_TIME:', #{key}), 0))")
    Integer lockRequest(@Param("key") String key);

    @Select("SELECT * FROM mom_mes.mes_workpiece_time_operation WHERE tenant_id=#{tenant} "
            + "AND factory_code=#{factory} AND request_id=#{request} AND del_flag='0'")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesWorkpieceTimeOperationEntity operation(@Param("tenant") Long tenant, @Param("factory") String factory,
                                              @Param("request") String request);

    @Select("SELECT * FROM mom_mes.mes_workpiece_time_session WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND arrange_id=#{arrange} AND identifier_type=#{type} AND identifier=#{identifier} AND del_flag='0' FOR UPDATE NOWAIT")
    MesWorkpieceTimeSessionEntity session(@Param("tenant") Long tenant, @Param("factory") String factory,
                                          @Param("arrange") Long arrange, @Param("type") String type, @Param("identifier") String identifier);

    @Select("SELECT * FROM mom_mes.mes_workpiece_time_session WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND arrange_id=#{arrange} AND status IN ('RUNNING','PAUSED') AND del_flag='0' AND id>#{after} "
            + "ORDER BY id LIMIT 1000 FOR UPDATE NOWAIT")
    List<MesWorkpieceTimeSessionEntity> lockSessions(@Param("tenant") Long tenant, @Param("factory") String factory,
                                                     @Param("arrange") Long arrange, @Param("after") Long after);

    @Select("SELECT * FROM mom_mes.mes_workpiece_time_session WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND arrange_id=#{arrange} AND del_flag='0' ORDER BY CASE WHEN status='FINISHED' THEN 1 ELSE 0 END,id DESC "
            + "LIMIT 100 OFFSET #{offset}")
    List<MesWorkpieceTimeSessionEntity> sessions(@Param("tenant") Long tenant, @Param("factory") String factory,
                                                 @Param("arrange") Long arrange, @Param("offset") Long offset);

    @Select("SELECT COUNT(*) FROM mom_mes.mes_workpiece_time_session WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND arrange_id=#{arrange} AND del_flag='0'")
    long sessionTotal(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("arrange") Long arrange);

    @Select("SELECT * FROM mom_mes.mes_workpiece_time_interval WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND arrange_id=#{arrange} AND del_flag='0' ORDER BY id DESC LIMIT 200 OFFSET #{offset}")
    List<MesWorkpieceTimeIntervalEntity> intervals(@Param("tenant") Long tenant, @Param("factory") String factory,
                                                   @Param("arrange") Long arrange, @Param("offset") Long offset);

    @Select("SELECT COUNT(*) FROM mom_mes.mes_workpiece_time_interval WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND arrange_id=#{arrange} AND del_flag='0'")
    long intervalTotal(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("arrange") Long arrange);

    @Select("SELECT * FROM mom_mes.mes_workpiece_time_interval WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND session_id=#{session} AND status='OPEN' AND del_flag='0' ORDER BY id LIMIT 2 FOR UPDATE NOWAIT")
    List<MesWorkpieceTimeIntervalEntity> openIntervals(@Param("tenant") Long tenant, @Param("factory") String factory,
                                                       @Param("session") Long session);

    @Select("<script>SELECT COUNT(*) FROM mom_mes.mes_task_report WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND transfer_code=#{identifier} AND task_code &lt;&gt; #{task} AND del_flag='0' "
            + "</script>")
    long knownSnConflicts(@Param("tenant") Long tenant, @Param("factory") String factory,
                          @Param("task") String task, @Param("identifier") String identifier);

    @Select("<script>SELECT COUNT(*) FROM mom_mes.mes_workpiece_time_session WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND identifier_type='SN' AND identifier=#{identifier} AND del_flag='0' "
            + "AND (task_code &lt;&gt; #{task} OR (arrange_id &lt;&gt; #{arrange} AND status &lt;&gt; 'FINISHED'))</script>")
    long knownSessionConflicts(@Param("tenant") Long tenant, @Param("factory") String factory,
                               @Param("task") String task, @Param("arrange") Long arrange, @Param("identifier") String identifier);

    @Insert("INSERT INTO mom_mes.mes_workpiece_time_session(id,tenant_id,factory_code,arrange_id,arrange_code,task_code,"
            + "produce_task_code,identifier,identifier_type,worker_code,device_code,status,pause_source,version_no,capture_basis,del_flag) "
            + "VALUES(#{id},#{tenantId},#{factoryCode},#{arrangeId},#{arrangeCode},#{taskCode},#{produceTaskCode},#{identifier},"
            + "#{identifierType},#{workerCode},#{deviceCode},#{status},#{pauseSource},#{versionNo},#{captureBasis},'0')")
    int insertSession(MesWorkpieceTimeSessionEntity row);

    @Update("UPDATE mom_mes.mes_workpiece_time_session SET status=#{row.status},pause_source=#{row.pauseSource},"
            + "version_no=#{row.versionNo} WHERE id=#{row.id} AND tenant_id=#{row.tenantId} AND factory_code=#{row.factoryCode} "
            + "AND version_no=#{before} AND del_flag='0'")
    int changeSession(@Param("row") MesWorkpieceTimeSessionEntity row, @Param("before") Long before);

    @Insert("INSERT INTO mom_mes.mes_workpiece_time_interval(id,tenant_id,factory_code,session_id,arrange_id,arrange_code,task_code,"
            + "produce_task_code,identifier,identifier_type,worker_code,device_code,kind,start_time,end_time,status,reason_code,del_flag) "
            + "VALUES(#{id},#{tenantId},#{factoryCode},#{sessionId},#{arrangeId},#{arrangeCode},#{taskCode},#{produceTaskCode},#{identifier},"
            + "#{identifierType},#{workerCode},#{deviceCode},#{kind},#{startTime},#{endTime},#{status},#{reasonCode},'0')")
    int insertInterval(MesWorkpieceTimeIntervalEntity row);

    @Update("UPDATE mom_mes.mes_workpiece_time_interval SET end_time=#{ended},status='CLOSED' WHERE id=#{id} "
            + "AND tenant_id=#{tenant} AND factory_code=#{factory} AND status='OPEN' AND del_flag='0' AND start_time <= #{ended}")
    int closeInterval(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id,
                      @Param("ended") LocalDateTime ended);

    @Insert("INSERT INTO mom_mes.mes_workpiece_time_operation(id,tenant_id,factory_code,request_id,request_hash,session_id,"
            + "arrange_id,actor_id,action,receipt_json,del_flag) VALUES(#{id},#{tenantId},#{factoryCode},#{requestId},#{requestHash},"
            + "#{sessionId},#{arrangeId},#{actorId},#{action},#{receiptJson},'0')")
    int insertOperation(MesWorkpieceTimeOperationEntity row);
}
