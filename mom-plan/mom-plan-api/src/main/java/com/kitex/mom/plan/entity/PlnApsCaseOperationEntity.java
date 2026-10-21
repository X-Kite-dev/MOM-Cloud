package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 智能 APS 案件不可变操作履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_aps_case_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "智能 APS 案件不可变操作履历")
public class PlnApsCaseOperationEntity extends BaseEntity<PlnApsCaseOperationEntity> {

    @Schema(description = "所属 APS 案件主键")
    private Long caseId;

    @Schema(description = "操作永久幂等请求号")
    private String requestId;

    @Schema(description = "操作命令内容指纹")
    private String commandFingerprint;

    @Schema(description = "受控领域动作代码")
    private String action;

    @Schema(description = "动作执行前案件状态")
    private String beforeStatus;

    @Schema(description = "动作执行后案件状态")
    private String afterStatus;

    @Schema(description = "动作执行前阶段")
    private String beforeStage;

    @Schema(description = "动作执行后阶段")
    private String afterStage;

    @Schema(description = "调用方期望的案件乐观锁版本")
    private Integer expectedVersion;

    @Schema(description = "动作完成后的案件乐观锁版本")
    private Integer resultVersion;

    @Schema(description = "调用方期望的案件上下文指纹")
    private String expectedContextFingerprint;

    @Schema(description = "动作完成后的案件上下文指纹")
    private String resultContextFingerprint;

    @Schema(description = "操作原因或决策依据")
    private String reason;

    @JsonIgnore
    @Schema(description = "动作执行前案件摘要（JSON），默认不直接输出")
    private String beforeSnapshot;

    @JsonIgnore
    @Schema(description = "动作执行后案件摘要（JSON），默认不直接输出")
    private String afterSnapshot;

    @Schema(description = "操作人编码")
    private String operatorCode;

    @Schema(description = "操作人名称")
    private String operatorName;

    @Schema(description = "操作时间")
    private LocalDateTime operationTime;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "工厂编码")
    private String factoryCode;
}
