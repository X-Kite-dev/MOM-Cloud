package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "APS多候选情景求解命令")
public class PlnSchedulingScenarioRunDTO {
    private String requestId;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @Schema(description = "智能APS工作台案件主键；旧排产入口不传")
    private Long apsCaseId;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @Schema(description = "智能APS工作台冻结的资源快照主键；必须与案件当前快照一致")
    private Long resourceSnapshotId;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(description = "智能APS工作台资源证据SHA-256；必须与当前快照一致")
    private String expectedResourceFingerprint;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(description = "通用工作台运行须携带来源预览指纹；已有成功请求仍按原命令重放")
    private String expectedFingerprint;
    @Schema(description = "Agent/事件调用时明确冻结的工厂；普通工作台可不传，由来源计划唯一确定")
    private String factoryCode;
    private String triggerType;
    private String cycleType;
    private LocalDate scheduleDate;
    private Integer planNum;
    private List<Long> targetIds;
    @Schema(description = "2至5个候选目标；不传时生成交期、均衡、换型三个标准候选")
    private List<PlnSchedulingCandidateProfileDTO> candidateProfiles;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(description = "同工厂已批准行业目标模板版本；候选须与该版本完全一致")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long objectiveTemplateId;
    @Schema(description = "Agent目录返回的模板内容指纹；传入时PLAN必须在求解前复核一致")
    private String expectedObjectiveTemplateFingerprint;
}
