package com.kitex.mom.qms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 由MOM其他业务模块发起CAPA/8D的受控内部命令。
 */
@Data
@Accessors(chain = true)
@Schema(description = "跨业务模块发起CAPA/8D命令")
public class QmsCapaExternalCreateCommand {

    @Schema(description = "来源模块生成的永久幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    @Schema(description = "来源类型，当前支持MES_CHANGE_POINT_PARETO", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sourceType;

    @Schema(description = "来源模块权威记录主键", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long sourceId;

    @Schema(description = "来源业务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sourceNo;

    @Schema(description = "来源聚合口径、时间窗、指标和指纹证据", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sourceEvidence;

    @Schema(description = "问题标题", requiredMode = Schema.RequiredMode.REQUIRED)
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

    @Schema(description = "计划关闭时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime dueTime;

    @Schema(description = "来源实际工厂编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String factoryCode;
}
