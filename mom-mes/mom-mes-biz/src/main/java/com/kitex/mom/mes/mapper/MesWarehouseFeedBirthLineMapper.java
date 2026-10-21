package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesWarehouseFeedBirthLineEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 出生逐源原账；单个JSON参数批量INSERT，避免五千条往返及PG绑定参数上限。 */
@Mapper
public interface MesWarehouseFeedBirthLineMapper extends MomBaseMapper<MesWarehouseFeedBirthLineEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("""
            INSERT INTO "mom_mes".mes_warehouse_feed_birth_line
              (id,tenant_id,factory_code,birth_id,sequence_no,stock_id,outflow_id,mutation_key,
               quantity,unit,source_snapshot,after_snapshot,flow_snapshot,evidence_version,
               evidence_snapshot,evidence_fingerprint,create_by,create_time,del_flag)
            SELECT (b->>'id')::bigint,(b->>'tenantId')::bigint,b->>'factoryCode',
              (b->>'birthId')::bigint,(b->>'sequenceNo')::integer,(b->>'stockId')::bigint,
              (b->>'outflowId')::bigint,b->>'mutationKey',(b->>'quantity')::numeric,b->>'unit',
              b->>'sourceSnapshot',b->>'afterSnapshot',b->>'flowSnapshot','MES_FEED_SOURCE_V1',
              raw,encode(sha256(convert_to('MES_FEED_SOURCE_V1|'||raw,'UTF8')),'hex'),
              #{actor},#{createdAt},'0'
            FROM jsonb_array_elements(CAST(#{snapshots} AS jsonb)) l(value)
            CROSS JOIN LATERAL (SELECT value#>>'{}' AS raw) x
            CROSS JOIN LATERAL (SELECT CAST(raw AS jsonb) AS b) y
            """)
    int insertAll(@Param("snapshots") String snapshots, @Param("actor") String actor,
                  @Param("createdAt") LocalDateTime createdAt);
}
