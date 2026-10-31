package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesInspectionRevisionResolutionEntity;
import com.kitex.mom.mes.entity.MesInspectionRevisionSealEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesInspectionRevisionExecutionMapper
        extends BaseMapper<MesInspectionRevisionSealEntity> {
    @Select("SELECT mom_mes.mom_inspection_revision_execution_ready_v1()")
    Integer ready();

    @Select(
            "SELECT * FROM mom_mes.mes_inspection_revision_seal WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    MesInspectionRevisionSealEntity seal(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_mes.mes_inspection_revision_resolution WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    MesInspectionRevisionResolutionEntity resolution(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Insert(
            "INSERT INTO"
                    + " mom_mes.mes_inspection_revision_resolution(id,tenant_id,factory_code,task_code,produce_task_code,inspection_id,gen_record_id,operation,intent_fingerprint,seal_fingerprint,event_id,event_fingerprint,payload,fingerprint,receipt,receipt_fingerprint,resolved_at)"
                    + " VALUES(#{id},#{tenantId},#{factoryCode},#{taskCode},#{produceTaskCode},#{inspectionId},#{genRecordId},#{operation},#{intentFingerprint},#{sealFingerprint},#{eventId},#{eventFingerprint},#{payload},#{fingerprint},#{receipt},#{receiptFingerprint},#{resolvedAt})")
    int resolve(MesInspectionRevisionResolutionEntity row);

    @Select(
            "SELECT COUNT(*) FROM mom_mes.mes_warehouse_live_stock x WHERE x.tenant_id=#{tenant}"
                    + " AND x.factory_code=#{factory} AND EXISTS(SELECT 1 FROM mom_mes.mes_produce_task"
                    + " p WHERE p.tenant_id=x.tenant_id AND p.factory_code=x.factory_code AND"
                    + " p.code=x.produce_task_code AND p.task_code=#{root})")
    long stockRows(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("root") String root);
}
