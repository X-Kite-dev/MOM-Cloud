package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.MesProcessCompositionRunEntity;
import com.kitex.mom.mes.entity.MesProcessCompositionTemplateEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 所有查询显式使用实际租户工厂；原号加锁后强制刷新MyBatis会话缓存。
 */
@Mapper
public interface MesProcessCompositionMapper {

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_PROCESS_COMPOSITION:',"
            + "#{tenant}, ':', #{factory}, ':', #{key}), 0))")
    Integer lock(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("key") String key);

    @Select("SELECT * FROM mom_mes.mes_process_composition_template WHERE tenant_id=#{tenant} "
            + "AND factory_code=#{factory} AND del_flag='0' AND id=#{id}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesProcessCompositionTemplateEntity template(@Param("tenant") Long tenant, @Param("factory") String factory,
                                                 @Param("id") Long id);

    @Select("SELECT * FROM mom_mes.mes_process_composition_template WHERE tenant_id=#{tenant} "
            + "AND factory_code=#{factory} AND del_flag='0' ORDER BY create_time DESC, id DESC LIMIT 200")
    List<MesProcessCompositionTemplateEntity> templates(@Param("tenant") Long tenant, @Param("factory") String factory);

    @Select("SELECT COALESCE(MAX(version_no),0) FROM mom_mes.mes_process_composition_template "
            + "WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0' AND code=#{code}")
    Integer maxVersion(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("code") String code);

    @Insert("INSERT INTO mom_mes.mes_process_composition_template(id,tenant_id,factory_code,code,name,version_no,"
            + "revision_no,status,definition_json,create_by,create_time,update_by,update_time,del_flag) "
            + "VALUES(#{id},#{tenantId},#{factoryCode},#{code},#{name},#{versionNo},#{revisionNo},#{status},"
            + "#{definitionJson},#{createBy},#{createTime},#{updateBy},#{updateTime},'0')")
    int insertTemplate(MesProcessCompositionTemplateEntity row);

    @Update("UPDATE mom_mes.mes_process_composition_template SET name=#{name},definition_json=#{definitionJson},"
            + "status=#{status},revision_no=revision_no+1,update_by=#{updateBy},update_time=#{updateTime} "
            + "WHERE id=#{id} AND tenant_id=#{tenantId} AND factory_code=#{factoryCode} "
            + "AND del_flag='0' AND status='DRAFT' AND revision_no=#{revisionNo}")
    int updateDraft(MesProcessCompositionTemplateEntity row);

    @Select("SELECT * FROM mom_mes.mes_process_composition_run WHERE tenant_id=#{tenant} "
            + "AND factory_code=#{factory} AND del_flag='0' AND request_id=#{requestId}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesProcessCompositionRunEntity request(@Param("tenant") Long tenant, @Param("factory") String factory,
                                           @Param("requestId") String requestId);

    @Select("SELECT * FROM mom_mes.mes_process_composition_run WHERE tenant_id=#{tenant} "
            + "AND factory_code=#{factory} AND del_flag='0' AND id=#{id}")
    MesProcessCompositionRunEntity run(@Param("tenant") Long tenant, @Param("factory") String factory,
                                       @Param("id") Long id);

    @Select("SELECT * FROM mom_mes.mes_process_composition_run WHERE tenant_id=#{tenant} "
            + "AND factory_code=#{factory} AND del_flag='0' ORDER BY create_time DESC LIMIT 50")
    List<MesProcessCompositionRunEntity> runs(@Param("tenant") Long tenant, @Param("factory") String factory);

    @Insert("INSERT INTO mom_mes.mes_process_composition_run(id,tenant_id,factory_code,request_id,request_fingerprint,"
            + "actor_id,template_id,template_version,template_name,definition_snapshot,nodes_json,task_codes_json,"
            + "create_by,create_time,del_flag) VALUES(#{id},#{tenantId},#{factoryCode},#{requestId},"
            + "#{requestFingerprint},#{actorId},#{templateId},#{templateVersion},#{templateName},"
            + "#{definitionSnapshot},#{nodesJson},#{taskCodesJson},#{createBy},#{createTime},'0')")
    int insertRun(MesProcessCompositionRunEntity row);
}
