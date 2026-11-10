package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesWarehouseFeedBirthLineEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 出生逐源原账；单个JSON参数批量INSERT，避免五千条往返及PG绑定参数上限。
 */
@Mapper
public interface MesWarehouseFeedBirthLineMapper extends MomBaseMapper<MesWarehouseFeedBirthLineEntity> {
    /**
     * 同事务写入逐源原账，逐条保留原字符串及其指纹。用 CTE 展开数组，兼容 PostgreSQL 方言的 SQL 日志解析器。
     *
     * @param snapshots 逐源证据原字符串数组
     * @param actor 原投料操作人
     * @param createdAt 原投料时间
     * @return 实际写入的来源条数
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("""
            WITH raw_sources AS (
                SELECT jsonb_array_elements_text(CAST(#{snapshots} AS jsonb)) AS raw
            ), sources AS (
                SELECT raw, CAST(raw AS jsonb) AS document
                FROM raw_sources
            )
            INSERT INTO "mom_mes".mes_warehouse_feed_birth_line
                (id, tenant_id, factory_code, birth_id, sequence_no, stock_id, outflow_id, mutation_key,
                 quantity, unit, source_snapshot, after_snapshot, flow_snapshot, evidence_version,
                 evidence_snapshot, evidence_fingerprint, create_by, create_time, del_flag)
            SELECT (document->>'id')::bigint, (document->>'tenantId')::bigint, document->>'factoryCode',
                (document->>'birthId')::bigint, (document->>'sequenceNo')::integer,
                (document->>'stockId')::bigint, (document->>'outflowId')::bigint, document->>'mutationKey',
                (document->>'quantity')::numeric, document->>'unit', document->>'sourceSnapshot',
                document->>'afterSnapshot', document->>'flowSnapshot', 'MES_FEED_SOURCE_V1',
                raw, encode(sha256(convert_to('MES_FEED_SOURCE_V1|' || raw, 'UTF8')), 'hex'),
                #{actor}, #{createdAt}, '0'
            FROM sources
            """)
    int insertAll(@Param("snapshots") String snapshots, @Param("actor") String actor,
                  @Param("createdAt") LocalDateTime createdAt);
}
