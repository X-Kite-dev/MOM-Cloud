package com.kitex.mom.plan.entity;

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

/** 生产计划受控变更的不可变操作履历，同时作为人工命令永久幂等账。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_issue_change_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "生产计划受控变更操作履历")
public class PlnIssueChangeOperationEntity extends BaseEntity<PlnIssueChangeOperationEntity> {
    /** 所属生产计划变更单主键。 */
    private Long changeId;
    /** 人工命令永久幂等请求号。 */
    private String requestId;
    /** 人工命令载荷的 SHA-256 指纹。 */
    private String requestFingerprint;
    /** SUBMIT、WITHDRAW、REVIEW_APPROVE、REVIEW_REJECT、REVIEW_STALE 或 RETRY。 */
    private String operationType;
    /** 操作前主状态；首次提交为空。 */
    private String beforeStatus;
    /** 操作后主状态。 */
    private String afterStatus;
    /** 申请、复核、撤回或续跑的业务说明。 */
    private String content;
    /** 操作人账号。 */
    private String operatorCode;
    /** 操作人姓名。 */
    private String operatorName;
    /** 操作发生时间。 */
    private LocalDateTime operationTime;
    /** 业务工厂编码，创建时从工厂上下文写入。 */
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
