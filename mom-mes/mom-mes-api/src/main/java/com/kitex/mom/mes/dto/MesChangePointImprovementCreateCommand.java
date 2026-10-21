package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 从变化点损失Pareto分组发起CAPA/8D改善的命令。
 */
@Data
@Schema(description = "变化点损失Pareto发起CAPA命令")
public class MesChangePointImprovementCreateCommand {

    @Schema(description = "客户端幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    @Schema(description = "聚合维度：CATEGORY/CONFIG/LOSS", requiredMode = Schema.RequiredMode.REQUIRED)
    private String dimension;

    @Schema(description = "聚合项编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String groupCode;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "基线开始时间，包含", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime baselineStartTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "基线结束时间，不包含", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime baselineEndTime;

    @Schema(description = "CAPA标题", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @Schema(description = "可验证的问题陈述", requiredMode = Schema.RequiredMode.REQUIRED)
    private String problemStatement;

    @Schema(description = "影响范围", requiredMode = Schema.RequiredMode.REQUIRED)
    private String impactScope;

    @Schema(description = "触发CAPA原因", requiredMode = Schema.RequiredMode.REQUIRED)
    private String triggerReason;

    @Schema(description = "严重度，1至10", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer severity;

    @Schema(description = "发生度，1至10", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer occurrence;

    @Schema(description = "探测度，1至10", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer detection;

    @Schema(description = "跨职能团队成员工号，多个以逗号分隔")
    private String teamMembers;

    @Schema(description = "CAPA协调人工号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String coordinatorCode;

    @Schema(description = "CAPA协调人姓名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String coordinatorName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "计划关闭时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime dueTime;
}
