package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Only committed generation intents backed by their actual immutable V2 command are recoverable.
 */
@Mapper
public interface MesInspectionGenerationPendingMapper {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT g.id,g.tenant_id,g.factory_code FROM mom_mes.mes_inspection_gen_record g JOIN"
                + " mom_mes.mes_qms_inspection_command c ON c.tenant_id=g.tenant_id AND"
                + " c.factory_code=g.factory_code AND c.request_id=g.source_request_id AND"
                + " c.source_task_code=g.produce_task_code WHERE g.id>#{after} AND g.del_flag='0'"
                + " AND c.del_flag='0' AND g.gen_flag='0' AND g.inspection_id IS NULL AND"
                + " g.inspection_type='FIRST' AND c.command_version='QMS_PROCEDURE_SOURCE_V2' AND"
                + " c.status IN ('REGISTERED','OUTBOUND_STARTED','QMS_CONFIRMED') ORDER BY g.id"
                + " LIMIT 100")
    List<MesInspectionGenRecordEntity> pendingAfter(@Param("after") long after);
}
