package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * APS A3 异岗审批事实。提交、决定和发布均在 PLAN 留痕；模型建议不能代替审批。
 */
@Data
@TenantTable
@TableName("pln_aps_a3_approval")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "APS A3 异岗审批事实")
public class PlnApsA3ApprovalEntity extends BaseEntity<PlnApsA3ApprovalEntity> {

    /** 被审批的统一排产案件。 */
    private Long caseId;
    /** 审批业务编号。 */
    private String approvalNo;
    /** PENDING、APPROVED、REJECTED、INVALIDATED 或 CONSUMED。 */
    private String status;
    /** 案件绑定的 APS 情景及 A2 候选。 */
    private Long scenarioId;
    private Long selectedTaskId;
    /** 送审时冻结的案件、情景和 A2 选择指纹。 */
    private String caseFingerprint;
    private String scenarioFingerprint;
    private String selectionFingerprint;
    /** 两次操作各自的永久幂等号。发布号由服务端在送审时生成。 */
    private String submitRequestId;
    private String decisionRequestId;
    private String publishRequestId;
    /** 来自登录身份的申请人和异岗审核人，不接受客户端填报。 */
    private String applicantCode;
    private String approverCode;
    private String submitReason;
    private String decisionReason;
    private LocalDateTime submittedTime;
    private LocalDateTime decidedTime;
    private LocalDateTime consumedTime;
    /** 与案件同一租户和工厂。 */
    private String factoryCode;
}
