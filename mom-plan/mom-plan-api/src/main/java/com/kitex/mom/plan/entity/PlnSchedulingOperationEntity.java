package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("pln_scheduling_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "APS工序级资源占用快照")
public class PlnSchedulingOperationEntity extends BaseEntity<PlnSchedulingOperationEntity> {

    private Long taskId;
    @Schema(description = "APS排产批次ID")
    private Long schedulingBatchId;
    @Schema(description = "APS排产批次号")
    private String schedulingBatchNo;
    @Schema(description = "SINGLE、SPLIT或MERGED")
    private String schedulingBatchType;
    @Schema(description = "批次来源计划及数量分配冻结快照")
    private String batchAllocationSnapshot;
    private Long issueId;
    private String issueNo;
    private String productCode;
    @Schema(description = "排产时产品族编码快照")
    private String schedulingFamilyCode;
    private String schedulingFamilyName;
    private BigDecimal plannedQuantity;

    private Long routeId;
    private String routeCode;
    private String routeName;
    private Long routeWorkingId;
    /** 排程实际采用的稳定路线节点；历史排程不从今日主数据回填。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(description = "来源路线节点编码；空为旧主工序合同")
    private String sourceRouteNodeCode;
    /** 节点前序与主工序前序分开冻结；新合同空字符串表示起点，null表示旧合同。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(description = "冻结前置节点编码，逗号分隔")
    private String predecessorNodeCodes;
    private Integer operationSeq;
    private String processCode;
    private String processName;
    private String predecessorCodes;
    @Schema(description = "是否按转移批量提前放行后序")
    private Boolean overlapEnabled;
    private BigDecimal transferBatchQuantity;
    @Schema(description = "达到转移批量并计入转运时间后的后序最早开工时刻")
    private LocalDateTime transferReadyTime;

    @Schema(description = "STATION、LINE、WORKSHOP或OUTSOURCE")
    private String resourceType;
    private String resourceCode;
    private String resourceName;
    @Schema(description = "资源并行槽位序号，从1开始")
    private Integer resourceSlotNo;
    @Schema(description = "跨工位共享资源组编码")
    private String resourceGroupCode;
    private String resourceGroupName;
    @Schema(description = "共享资源组排程时容量")
    private Integer resourceGroupCapacity;
    @Schema(description = "共享资源组实际占用槽位")
    private Integer resourceGroupSlotNo;
    private String candidateResourceSnapshot;

    @Schema(description = "工位绑定且由排程实际选中的设备编码")
    private String deviceCode;
    private String deviceName;
    @Schema(description = "排程时TPM设备可用性窗口快照")
    private String deviceAvailabilitySnapshot;

    @Schema(description = "由APS实际选中并占用的执行员工编码")
    private String employeeCode;
    private String employeeName;
    @Schema(description = "排程时人员技能、证书与在岗窗口快照")
    private String workforceAvailabilitySnapshot;

    @Schema(description = "APS实际选择并联合占用的工装编码，逗号分隔")
    private String toolingCodes;
    private String toolingNames;
    @Schema(description = "工装要求、设备兼容、寿命和不可用窗口证据JSON")
    private String toolingAllocationSnapshot;
    @Schema(description = "本工序按工装编码记录的计划寿命消耗JSON")
    private String toolingLifeUsageSnapshot;

    private Long setupMinutes;
    @Schema(description = "顺序相关清洁换型占用分钟，不含工序固定准备时间")
    private Long changeoverMinutes;
    private String precedingFamilyCode;
    private String precedingProductCode;
    @Schema(description = "命中的工位/设备/工装换型规则证据JSON")
    private String changeoverRuleSnapshot;
    private Long runMinutes;
    private Long disassemblyMinutes;
    @Schema(description = "生效的实际工时校准建议ID；空表示沿用工序/工位标准")
    private Long durationCalibrationProfileId;
    private Integer durationCalibrationVersionNo;
    @Schema(description = "生效校准建议的参数、版本与MES证据综合指纹")
    private String durationCalibrationFingerprint;
    @Schema(description = "工序排程采用的校准模型、证据指纹及参数冻结快照")
    private String durationCalibrationSnapshot;
    private Long transferMinutes;
    private Long activeMinutes;

    private LocalDateTime scheduledStartTime;
    private LocalDateTime scheduledEndTime;

    @Schema(description = "本工序全部投料汇合后的最早开工时刻")
    private LocalDateTime materialAvailableTime;
    @Schema(description = "本工序物料约束状态")
    private String materialConstraintStatus;
    @Schema(description = "本工序物料约束摘要")
    private String materialConstraintSummary;
    @Schema(description = "本工序投料路径与可用时刻冻结快照")
    private String materialConstraintSnapshot;
    @Schema(description = "本工序物料约束证据指纹")
    private String materialConstraintFingerprint;
    @Schema(description = "MRP BOM路径证据综合指纹")
    private String materialLineageFingerprint;

    private String scheduleStatus;
    private Boolean hardBlocked;
    private String constraintSummary;
    private String sourceSnapshot;

    private Boolean lockStatus;
    private LocalDateTime lockTime;

    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
