package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 假设派工范围的只读试算，不接受客户端指定租户工厂，不产生派工许可。
 */
@Data
public class MesExecutionTemplatePreviewQueryDTO {
    private String materialCode;
    private String routeCode;
    private String processRouteCode;
    private String routeNodeCode;
    private String workshopCode;
    @Schema(description = "假设派工选择的工位集合，逗号分隔；只要包含模板工位即匹配")
    private String worksiteCodes;
    @Schema(description = "原生节点执行时为NODE_EXECUTION_V1；普通模式为空")
    private String routeExecutionSchemaVersion;
    private Integer current = 1;
    private Integer size = 20;
    private Boolean matchedOnly = false;
}
