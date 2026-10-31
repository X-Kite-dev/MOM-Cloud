package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.dto.MesDispatchHistoryDTO.Query;
import com.kitex.mom.mes.entity.MesDispatchHistoryNoteEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MesDispatchHistoryMapper {
    List<MesTaskArrangeEntity> page(@Param("tenant") Long tenant, @Param("factory") String factory,
                                    @Param("q") Query query, @Param("limit") int limit, @Param("offset") long offset);

    long total(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("q") Query query);

    @Select("SELECT * FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND id=#{id} AND del_flag='0'")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesTaskArrangeEntity arrange(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select("SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND code=#{code} AND del_flag='0' LIMIT 2")
    List<MesTaskEntity> tasks(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("code") String code);

    @Select("SELECT * FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND code=#{code} AND task_code=#{task} AND del_flag='0' LIMIT 2")
    List<MesProduceTaskEntity> processes(@Param("tenant") Long tenant, @Param("factory") String factory,
                                         @Param("code") String code, @Param("task") String task);

    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('MES_DISPATCH_HISTORY_NOTE:',#{key}),0))")
    void lockRequest(@Param("key") String key);

    @Select("SELECT mom_mes.mom_dispatch_history_ready()")
    Boolean ready();

    @Select("SELECT * FROM mom_mes.mes_dispatch_history_note WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND request_id=#{request} AND del_flag='0'")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesDispatchHistoryNoteEntity noteRequest(@Param("tenant") Long tenant, @Param("factory") String factory,
                                             @Param("request") String request);

    @Select("SELECT * FROM mom_mes.mes_dispatch_history_note WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND arrange_id=#{id} AND del_flag='0' ORDER BY create_time DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<MesDispatchHistoryNoteEntity> notes(@Param("tenant") Long tenant, @Param("factory") String factory,
                                             @Param("id") Long id, @Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM mom_mes.mes_dispatch_history_note WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
            + "AND arrange_id=#{id} AND del_flag='0'")
    long noteTotal(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Insert("INSERT INTO mom_mes.mes_dispatch_history_note(id,tenant_id,factory_code,arrange_id,arrange_code,task_code,"
            + "produce_task_code,request_id,request_hash,actor_id,actor_code,note,source_fingerprint,source_snapshot,create_time,del_flag) "
            + "VALUES(#{id},#{tenantId},#{factoryCode},#{arrangeId},#{arrangeCode},#{taskCode},#{produceTaskCode},#{requestId},"
            + "#{requestHash},#{actorId},#{actorCode},#{note},#{sourceFingerprint},#{sourceSnapshot},#{createTime},'0')")
    int insertNote(MesDispatchHistoryNoteEntity note);
}
