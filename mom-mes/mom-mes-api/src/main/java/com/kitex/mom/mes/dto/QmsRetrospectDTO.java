package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 质量检测
 *
 * @author kitex
 * @since 2025-04-24
 */
@Data
public class QmsRetrospectDTO {

    @Schema(description = "质检任务编号")
    private Long inspectionId;

    @Schema(description = "质检类型")
    private String inspectionType;

    @Schema(description = "质检结果")
    private String inspectionResult;

    @Schema(description = "质检项")
    private List<InspectionItems> items;

    @Data
    public static class InspectionItems {

        @Schema(description = "检验项")
        private String itemName;

        @Schema(description = "质检结果")
        private String itemResult;

        @Schema(description = "质检要求")
        private String itemDemand;

        @Schema(description = "质检位置")
        private String itemPosition;
    }
}
