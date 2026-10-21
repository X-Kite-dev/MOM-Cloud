package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * MOM 向 ERP 发送的规范业务信封。
 *
 * <p>信封只描述已经在领域事务中成立的计划或执行事实；ERP 连接器不得据此反向修改
 * MOM 的正式合同、排程或报工主记录。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "MOM向ERP发送的规范业务信封")
public class PlnErpPostingEnvelope {
    private String contractVersion;
    private String requestId;
    private String eventType;
    private String sourceModule;
    private String sourceDocumentNo;
    private String sourceDocumentVersion;
    private String erpSystemCode;
    private String factoryCode;
    private String mrpAuthority;
    private String productionOrderSyncMode;
    private String substituteGovernanceMode;
    private String planningPolicyId;
    private Integer planningPolicyVersion;
    private String planningPolicyFingerprint;
    private String formalPlanContractNo;
    private Integer formalPlanContractVersion;
    private String formalPlanContractFingerprint;
    /** 领域载荷的规范 JSON；不能包含 ERP 密钥、任意回调地址或浏览器令牌。 */
    private String payloadJson;
    private LocalDateTime occurredTime;
}
