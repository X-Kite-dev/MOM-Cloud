package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("qms_capa")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "CAPA/8D纠正预防措施主单")
public class QmsCapaEntity extends BaseEntity<QmsCapaEntity> {
    private String capaNo;
    private String requestId;
    private String sourceType;
    private Long sourceId;
    private String sourceNo;
    private String title;
    private String problemStatement;
    private String impactScope;
    private String triggerReason;
    private Integer severity;
    private Integer occurrence;
    private Integer detection;
    private Integer riskPriorityNo;
    private String teamMembers;
    private String coordinatorCode;
    private String coordinatorName;
    private String status;
    private Integer cycleNo;
    private Integer recurrenceCount;
    private LocalDateTime dueTime;
    private String effectivenessCriteria;
    private LocalDateTime effectivenessReviewTime;
    private String standardizationScope;
    private String effectivenessResult;
    private String effectivenessRemark;
    private String effectivenessEvidence;
    private String verifierCode;
    private String verifierName;
    private LocalDateTime verificationTime;
    private String lessonsLearned;
    private LocalDateTime closedTime;
    private String cancelReason;
    @TableField(exist = false)
    private Boolean overdueFlag;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
