package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 插单联合重排的逐工序前后证据。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_insertion_replan_line")
@EqualsAndHashCode(callSuper = true)
public class PlnInsertionReplanLineEntity extends BaseEntity<PlnInsertionReplanLineEntity> {
    private Long caseId;
    private Long issueImpactId;
    private Long issueId;
    private String issueNo;
    private Boolean insertionIssue;
    private Long originalOperationId;
    private Long proposalOperationId;
    private Integer operationSeq;
    private String processCode;
    /** 原正式操作的冻结节点；旧案件保持空，不能按当前主数据补造。 */
    private String sourceRouteNodeCode;
    private String processName;
    /** INSERT、MOVE、UNCHANGED。 */
    private String actionType;
    private LocalDateTime originalStartTime;
    private LocalDateTime originalEndTime;
    private LocalDateTime proposedStartTime;
    private LocalDateTime proposedEndTime;
    private String originalResourceCode;
    private String proposedResourceCode;
    private String originalDeviceCode;
    private String proposedDeviceCode;
    private String originalEmployeeCode;
    private String proposedEmployeeCode;
    private String originalToolingCodes;
    private String proposedToolingCodes;
    private Boolean resourceReassigned;
    private Long shiftMinutes;
    private String originalOperationSnapshot;
    private String proposedOperationSnapshot;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
