package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** MPS发布跨账核对视图。只读证据不代表新审批，也不直接修改MES现场事实。 */
@Data
@Accessors(chain = true)
public class PlnMpsPublicationRecoveryDTO {
    @Schema(description = "局部重排案件主键")
    private Long caseId;

    @Schema(description = "根生产计划号")
    private String issueNo;

    @Schema(description = "PLAN权威案件状态")
    private String caseStatus;

    @Schema(description = "NO_APPLICATION/PENDING_PLAN_ACK/COMPENSATED/RECONCILE_REQUIRED/PLAN_COMMITTED")
    private String state;

    @Schema(description = "原业务发布永久号；不能用新号覆盖")
    private String businessRequestId;

    @Schema(description = "当前MES应用子号；补偿后重投使用有界确定性分段号")
    private String mesRequestId;

    @Schema(description = "原MES实名发布账号，恢复必须仍为此账号且有实际工厂write授权")
    private String appliedBy;

    @Schema(description = "已成功应用的原MES时刻，不是本次观察时刻")
    private LocalDateTime appliedTime;

    @Schema(description = "当前重投段序号，0为首次应用，最多10段")
    private Integer retrySegment;

    @Schema(description = "当前MES执行指纹，实际接收/开工/报工推进保留")
    private String currentMesFingerprint;

    @Schema(description = "当前账号可按原号尝试恢复；重投仍需完整实时复核")
    private Boolean recoverable;

    @Schema(description = "不完整、漂移、多原号或身份不匹配原因")
    private List<String> gaps = new ArrayList<>();
}
