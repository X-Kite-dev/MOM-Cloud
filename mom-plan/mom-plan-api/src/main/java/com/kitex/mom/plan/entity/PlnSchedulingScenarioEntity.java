package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("pln_scheduling_scenario")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "APS多候选情景头及选择审计")
public class PlnSchedulingScenarioEntity extends BaseEntity<PlnSchedulingScenarioEntity> {
    private String scenarioNo;
    private String requestId;
    private String requestFingerprint;
    private String triggerType;
    private String cycleType;
    private LocalDate scheduleDate;
    private Integer planNum;
    private String sourceIssueIdsSnapshot;
    private String sourceFingerprint;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String inputSnapshot;
    private String inputFingerprint;
    private String ruleCode;
    private Integer ruleVersion;
    @Schema(description = "EVALUATING、EVALUATED、SELECTED、PUBLISHED、PARTIAL、FAILED、CANCELLED或EXPIRED")
    private String scenarioStatus;
    private Integer candidateCount;
    @Schema(description = "后续专业调整派生的候选数量；初始求解候选数量保持不可变")
    private Integer derivedCandidateCount;
    private Long selectedTaskId;
    private String selectionRequestId;
    private String selectionFingerprint;
    private String selectionReason;
    private String selectedBy;
    private LocalDateTime selectedTime;
    @Schema(description = "Agent正式锁定的永久幂等号；普通工作台直接发布时可为空")
    private String publishRequestId;
    private String publishRequestFingerprint;
    private Long agentApprovalId;
    private String agentApprovalNo;
    private String publishedBy;
    private LocalDateTime publishedTime;
    private LocalDateTime expiresTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
