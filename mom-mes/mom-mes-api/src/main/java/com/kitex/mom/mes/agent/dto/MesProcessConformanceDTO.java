package com.kitex.mom.mes.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MES 工单冻结工艺与已生成工序任务的一致性证据。
 */
public final class MesProcessConformanceDTO {

    private MesProcessConformanceDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES 工艺执行一致性受控查询")
    public static class Query {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;

        @NotBlank
        @Size(max = 100)
        private String taskCode;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES 工艺执行一致性权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private LocalDateTime observedAt;
        private WorkOrder workOrder;
        private Summary summary;
        private List<OperationConformance> operations = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class WorkOrder {
        private Long id;
        private String taskCode;
        private String status;
        private String materialCode;
        private String materialName;
        private Long processRouteId;
        private String processRouteCode;
        private String processSnapshotFingerprint;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private Integer expectedOperationCount;
        private Integer generatedOperationCount;
        private Integer consistentOperationCount;
        private Integer warningOperationCount;
        private Integer criticalOperationCount;
        private Boolean processContractConsistent;
    }

    @Data
    @Accessors(chain = true)
    public static class OperationConformance {
        private String processCode;
        private String processName;
        private Integer expectedOrder;
        private Long operationId;
        private String operationTaskCode;
        private String operationStatus;
        private Integer actualOrder;
        private String plannedWorksiteCode;
        private String plannedDeviceCode;
        private Boolean routeIdentityMatched;
        private Boolean processSnapshotMatched;
        private Boolean graphMatched;
        private Boolean transferPolicyMatched;
        private Boolean plannedWorksiteAllowed;
        private Boolean apsLockValid;
        private String conformanceLevel;
        private List<String> issues = new ArrayList<>();
    }
}
