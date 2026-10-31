package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/** 原耗料关联的业务处置只读目录，不生成差异、质量案件或执行命令。 */
public final class MesMaterialDispositionDTO {
    private MesMaterialDispositionDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @Schema(description = "精确原工单")
        private String taskCode;
        @Schema(description = "原派工，可选；传入时必须精确匹配")
        private String arrangeCode;
        private Integer current = 1;
        private Integer size = 20;
    }

    @Data
    @Accessors(chain = true)
    public static class Result {
        private String version = "MES_MATERIAL_DISPOSITION_V1";
        private String tenantId;
        private String factoryCode;
        private String recordKey;
        private String taskCode;
        private String arrangeCode;
        @Schema(description = "RECONCILIATION或OUTPUT_QUALITY，后者不是原料质量根因认定")
        private String section;
        @Schema(description = "LINKED/NO_LINK/EMPTY；失败通过原异常响应返回")
        private String association;
        private String message;
        private LocalDateTime observedAt;
        private Integer current;
        private Integer size;
        private Long total;
        private List<Item> records;
    }

    @Data
    @Accessors(chain = true)
    public static class Item {
        @Schema(description = "原差异或原MES隔离案件主键，字符串保持精度")
        private String id;
        private String title;
        @Schema(description = "原差异状态或仅MES实物核定状态，不表示NCR/CAPA结案")
        private String status;
        private String reservationNo;
        private String requirementMaterialCode;
        private String materialCode;
        private String ownerName;
        private LocalDateTime lastFoundTime;
        @Schema(description = "仅产出质量：原真实NG报告主键")
        private String ngReportId;
        private String resourceType;
        private String unit;
        private String originalQuantity;
        private String remainingQuantity;
        private String reservedQuantity;
        private String verifiedQuantity;
    }
}
