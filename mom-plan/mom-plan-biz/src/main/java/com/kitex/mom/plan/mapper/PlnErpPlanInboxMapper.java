package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnErpPlanInboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** ERP 正式计划收件箱 Mapper；JSON 载荷以 PostgreSQL jsonb 显式写入。 */
@Mapper
public interface PlnErpPlanInboxMapper extends MomBaseMapper<PlnErpPlanInboxEntity> {

    /**
     * 保存已经完成结构和权威核验的收件记录。
     *
     * <p>显式转换 jsonb，避免 PostgreSQL 将 JDBC 字符串误判为 varchar。</p>
     */
    @Insert("""
            INSERT INTO pln_erp_plan_inbox (
              id, request_id, request_fingerprint, source_system_code, source_factory_code,
              source_plan_no, source_plan_version, source_content_fingerprint,
              normalized_payload_json, status, rejection_reason, contract_no, contract_version,
              planning_policy_id, planning_policy_version, planning_policy_fingerprint,
              verified_time, factory_code, tenant_id, create_by, create_time, del_flag
            ) VALUES (
              #{id}, #{requestId}, #{requestFingerprint}, #{sourceSystemCode}, #{sourceFactoryCode},
              #{sourcePlanNo}, #{sourcePlanVersion}, #{sourceContentFingerprint},
              CAST(#{normalizedPayloadJson} AS jsonb), #{status}, #{rejectionReason}, #{contractNo},
              #{contractVersion}, #{planningPolicyId}, #{planningPolicyVersion},
              #{planningPolicyFingerprint}, #{verifiedTime}, #{factoryCode}, #{tenantId},
              #{createBy}, #{createTime}, #{delFlag}
            )
            """)
    int insertVerified(PlnErpPlanInboxEntity entity);
}
