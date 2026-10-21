package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 插单联合重排的逐计划影响、交期和MES证据。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_insertion_replan_issue")
@EqualsAndHashCode(callSuper = true)
public class PlnInsertionReplanIssueEntity extends BaseEntity<PlnInsertionReplanIssueEntity> {
    private Long caseId;
    private Long issueId;
    private String issueNo;
    private String productCode;
    private String productName;
    private Boolean insertionIssue;
    private Long originalTaskId;
    private LocalDate demandTime;
    private LocalDateTime originalStartTime;
    private LocalDateTime originalEndTime;
    private LocalDateTime proposedStartTime;
    private LocalDateTime proposedEndTime;
    private String originalDeliveryRisk;
    private String proposedDeliveryRisk;
    private Long delayMinutes;
    private Integer movedOperationCount;
    private Integer reassignedOperationCount;
    private String originalIssueSnapshot;
    private String originalScheduleSnapshot;
    private String originalScheduleFingerprint;
    private String proposedScheduleSnapshot;
    private String proposedScheduleFingerprint;
    private String mesBeforeSnapshot;
    private String mesBeforeFingerprint;
    private String mesPublishedFingerprint;
    private String mesRollbackFingerprint;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
