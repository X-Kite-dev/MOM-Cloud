package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class MesApsOperationSnapshotDTO {
    @Schema(description = "下达时原APS工序ID；历史缺失不回填")
    private Long id;
    @Schema(description = "下达时原APS候选任务ID")
    private Long taskId;
    @Schema(description = "冻结产品编码")
    private String productCode;
    @Schema(description = "冻结计划数量，不以当前完成量回填")
    private BigDecimal plannedQuantity;
    @Schema(description = "原APS计划运行分钟，历史未知为null")
    private Long runMinutes;
    @Schema(description = "原APS准备占用分钟，既有引擎已并入顺序换型；固定准备须扣除changeover")
    private Long setupMinutes;
    @Schema(description = "原APS顺序相关换型分钟，不与RUN段混比")
    private Long changeoverMinutes;
    @Schema(description = "原APS拆卸分钟")
    private Long disassemblyMinutes;
    @Schema(description = "原APS总有效占用分钟，等于setup含换型+run+disassembly，不重复加changeover")
    private Long activeMinutes;
    private Long schedulingBatchId;
    private String schedulingBatchNo;
    private String schedulingBatchType;
    private String batchAllocationSnapshot;
    /**
     * 原排程路线；仅用于新下达比对，不回写既有生产快照。
     */
    private String routeCode;
    private Long routeWorkingId;
    /**
     * 新APS合同的稳定节点；null仅为旧主工序合同。
     */
    private String sourceRouteNodeCode;
    /**
     * 新合同的冻结前序节点；空串是起点，null为旧合同。
     */
    private String predecessorNodeCodes;
    private Integer operationSeq;
    private String predecessorCodes;
    private Boolean overlapEnabled;
    private BigDecimal transferBatchQuantity;
    private LocalDateTime transferReadyTime;
    private String processCode;
    private String processName;
    private String resourceType;
    private String resourceCode;
    private String resourceName;
    private Integer resourceSlotNo;
    private String resourceGroupCode;
    private String resourceGroupName;
    private Integer resourceGroupCapacity;
    private Integer resourceGroupSlotNo;
    private String deviceCode;
    private String deviceName;
    private String employeeCode;
    private String employeeName;
    private LocalDateTime scheduledStartTime;
    private LocalDateTime scheduledEndTime;
    private String scheduleStatus;
    private Boolean hardBlocked;
    private Boolean lockStatus;
    private String workforceAvailabilitySnapshot;
    private String deviceAvailabilitySnapshot;
    private String toolingCodes;
    private String toolingNames;
    private String toolingAllocationSnapshot;
    private String toolingLifeUsageSnapshot;
}
