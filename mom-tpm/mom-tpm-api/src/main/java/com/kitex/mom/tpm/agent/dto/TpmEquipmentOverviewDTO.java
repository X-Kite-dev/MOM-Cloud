package com.kitex.mom.tpm.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * TPM 设备状态、维修、保养和点巡检概览模型，供智能体只读问数使用。
 */
public final class TpmEquipmentOverviewDTO {

    private TpmEquipmentOverviewDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "TPM 设备运行与异常概览受控查询")
    public static class Query {
        @NotBlank
        private String factoryCode;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        @Min(1)
        @Max(50)
        private Integer limit = 20;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "TPM 设备运行与异常概览权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private LocalDateTime rangeStart;
        private LocalDateTime rangeEnd;
        private LocalDateTime observedAt;
        private Summary summary;
        private List<ExceptionItem> exceptionItems = new ArrayList<>();
        /** 已知能力边界，不代表本次查询缺数，也不应让Agent运行降级为PARTIAL。 */
        private List<String> limitations = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private Long registeredDeviceCount;
        private Long activeDeviceCount;
        private Long runningDeviceCount;
        private Long idleDeviceCount;
        private Long maintenanceDeviceCount;
        private Long repairingDeviceCount;
        private Long pausedDeviceCount;
        private Long unavailableDeviceCount;
        private BigDecimal currentAvailabilityRate;
        private Long repairActivityCount;
        private Long openRepairCount;
        private Long maintenanceActivityCount;
        private Long pendingMaintenanceCount;
        private Long processingMaintenanceCount;
        private Long overdueMaintenanceCount;
        private Long checkActivityCount;
        private Long pendingCheckCount;
        private Long processingCheckCount;
        private Long failedCheckCount;
        private Long blockedCheckCount;
    }

    @Data
    @Accessors(chain = true)
    public static class ExceptionItem {
        /**
         * DEVICE_STATE、REPAIR、MAINTENANCE 或 CHECK。
         */
        private String sourceType;
        /**
         * 维修任务的执行类型：internal 或 outsourcing；非维修来源为空。
         */
        private String repairType;
        private Long taskId;
        private String taskCode;
        private String deviceCode;
        private String deviceName;
        private String status;
        private String statusName;
        private String exceptionType;
        private String description;
        private String ownerName;
        private LocalDateTime occurredAt;
        private LocalDateTime plannedAt;
        private LocalDateTime expectedRecoveryTime;
        private Boolean overdue;
        private String blockStatus;
    }
}
