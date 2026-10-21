package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(value = {
        "reportId", "submittedIdentifier", "identifierMappingId", "identifierType", "identifierResolutionSnapshot",
        "extensionEvidenceSnapshot", "executionKind", "routeReturnId", "identifierRouteVersion",
        "identifierBindingId", "identifierBindingVersion", "identifierNodeAction", "identifierBindingSnapshot",
        "carrierOperationId", "carrierCode", "correctionPermitId", "sourceEventId", "sourceEventTime",
        "qualityGateMode", "qualityGateStatus", "qualityGateMessage", "qualityTaskIds", "qualityEvidenceFingerprint",
        "qualityEvidenceSnapshot", "qualityQuantityStatus", "qualityReleasedNum", "qualityPendingNum", "qualityRejectedNum",
        "deviceGateStage", "deviceGateAllowed", "deviceGateState", "deviceGateStateName", "deviceGateMessage",
        "deviceGateCheckTime", "stationPassFingerprint", "stationPassValidationSnapshot",
        "task", "taskArrange", "matCode", "matName", "matSpec", "matUnit",
        "completeNum", "noticeNum", "type", "taskType", "factoryCode", "deviceName"
}, allowGetters = true)
public class MesTaskReportVO {

    @Schema(hidden = true)
    private Long reportId;

    @Schema(description = "报工请求唯一号，用于网络重试幂等", maxLength = 64)
    private String requestId;

    @Schema(description = "流转码/条码")
    private String transferCode;

    /**
     * 绑定节点第二次扫描的外码；使用/释放节点仍在transferCode提交现场扫描值。
     */
    @Schema(description = "BIND节点的第二个扫描值：外码，最长200字符")
    private String externalIdentifier;

    /**
     * 使用/释放前由只读预览取得的具体实例，必须按文本传递，不能只传外码。
     */
    @Schema(description = "USE/RELEASE预览取得的活动实例ID文本")
    private String expectedBindingId;

    /**
     * 占用生命周期版本，不是每一次USE的次数；并行节点不相互使采集失效。
     */
    @Schema(description = "USE/RELEASE采集时的实例生命周期版本")
    private Integer expectedBindingVersion;

    @Schema(hidden = true)
    private Long identifierBindingId;

    @Schema(hidden = true)
    private Integer identifierBindingVersion;

    @Schema(hidden = true)
    private String identifierNodeAction;

    @Schema(hidden = true)
    private String identifierBindingSnapshot;

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

    @Schema(hidden = true)
    private String executionKind;

    @Schema(hidden = true)
    private Long routeReturnId;

    @Schema(hidden = true)
    private Integer identifierRouteVersion;

    @Schema(hidden = true)
    private Long carrierOperationId;

    @Schema(hidden = true)
    private String carrierCode;

    @Schema(description = "完成数量")
    private BigDecimal completeNum;

    @Schema(description = "合格数量")
    private BigDecimal passNum;

    @Schema(description = "受控重报许可号；仅纠正后补报时填写", maxLength = 40)
    private String correctionPermitNo;

    @Schema(hidden = true)
    private Long correctionPermitId;

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
