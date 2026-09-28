package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 生产报工
 *
 * @author kitex
 * @since 2025-05-13
 */
@Data
public class MesTaskReportVO {

    @Schema(hidden = true)
    private Long reportId;

    @Schema(description = "报工请求唯一号，用于网络重试幂等", maxLength = 64)
    private String requestId;

    @Schema(description = "流转码/条码")
    private String transferCode;

    @Schema(hidden = true)
    private String submittedIdentifier;

    @Schema(hidden = true)
    private Long identifierMappingId;

    @Schema(hidden = true)
    private String identifierType;

    @Schema(hidden = true)
    private String identifierResolutionSnapshot;

    @Schema(hidden = true)
    private String extensionEvidenceSnapshot;

    @Schema(description = "完成数量")
    private BigDecimal completeNum;

    @Schema(description = "合格数量")
    private BigDecimal passNum;

    @Schema(description = "派工单号")
    private String arrangeCode;

    @Schema(description = "任务单号")
    private String taskCode;

    @Schema(description = "工序任务单号")
    private String produceTaskCode;

    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "物料规格")
    private String matSpec;

    @Schema(description = "物料基本单位")
    private String matUnit;

    @Schema(description = "总需求数量")
    private BigDecimal noticeNum;

    @Schema(description = "类型 手动 自动")
    private String type;

    @Schema(description = "自动工位人工接管原因；手工报工自动工位时必填", maxLength = 500)
    private String manualOverrideReason;

    @Schema(hidden = true)
    private String sourceEventId;

    @Schema(hidden = true)
    private LocalDateTime sourceEventTime;

    @Schema(description = "实际执行人员编码；仅系统集成和设备事件入口使用")
    private String operatorCode;

    @Schema(description = "实际执行人员名称；仅系统集成和设备事件入口使用")
    private String operatorName;

    @Schema(hidden = true)
    private String qualityGateMode;

    @Schema(hidden = true)
    private String qualityGateStatus;

    @Schema(hidden = true)
    private String qualityGateMessage;

    @Schema(hidden = true)
    private String qualityTaskIds;

    @Schema(hidden = true)
    private String qualityEvidenceFingerprint;

    @Schema(hidden = true)
    private String qualityEvidenceSnapshot;

    @Schema(hidden = true)
    private String qualityQuantityStatus;

    @Schema(hidden = true)
    private BigDecimal qualityReleasedNum;

    @Schema(hidden = true)
    private BigDecimal qualityPendingNum;

    @Schema(hidden = true)
    private BigDecimal qualityRejectedNum;

    @Schema(hidden = true)
    private String deviceGateStage;

    @Schema(hidden = true)
    private Boolean deviceGateAllowed;

    @Schema(hidden = true)
    private String deviceGateState;

    @Schema(hidden = true)
    private String deviceGateStateName;

    @Schema(hidden = true)
    private String deviceGateMessage;

    @Schema(hidden = true)
    private LocalDateTime deviceGateCheckTime;

    @Schema(description = "包装规则编码")
    private String wrapRuleCode;

    @Schema(description = "箱码")
    private String wrapCode;

    @Schema(description = "容量")
    private Integer capacity;

    @Schema(description = "设备编码")
    private String deviceCode;

    @Schema(description = "设备名称")
    private String deviceName;

    @Schema(description = "工单类型")
    private String taskType;

    @Schema(description = "工作中心")
    private String worksiteCode;


    // 派工任务
    private MesTaskArrangeEntity taskArrange;

    /**
     * 生产工单
     */
    private MesTaskEntity task;

    private String factoryCode;

    @Schema(hidden = true)
    private String stationPassFingerprint;

    @Schema(hidden = true)
    private String stationPassValidationSnapshot;

}
