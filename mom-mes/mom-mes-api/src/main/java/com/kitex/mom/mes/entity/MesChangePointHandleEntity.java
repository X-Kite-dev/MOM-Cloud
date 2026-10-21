package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 变化点信息处理 实体类
 *
 * @author kitex
 * @since 2025-05-26
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_change_point_handle")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "变化点信息处理")
public class MesChangePointHandleEntity extends BaseEntity<MesChangePointHandleEntity> {

    @Schema(description = "变化点事件编码")
    private String eventCode;

    @Schema(description = "来源永久幂等请求号")
    private String requestId;

    /**
     * 变化点编码
     */
    @Schema(description = "变化点编码")
    private String configCode;

    /**
     * 变化点类型
     */
    @Schema(description = "变化点类型")
    private String configName;

    /**
     * 变化点内容
     */
    @Schema(description = "变化点内容")
    private String configContent;

    /**
     * 等级
     */
    @Schema(description = "等级")
    private String configLevel;

    /**
     * 上限
     */
    @Schema(description = "上限（配置快照）")
    private BigDecimal upperLimit;

    /**
     * 下限
     */
    @Schema(description = "下限（配置快照）")
    private BigDecimal lowerLimit;

    @Schema(description = "产品条码/流转码")
    private String transferCode;

    @Schema(description = "产线编码")
    private String lineCode;

    @Schema(description = "工序编码")
    private String routeCode;

    private String taskCode;
    private String produceTaskCode;
    private String arrangeCode;
    private String deviceCode;
    private String worksiteCode;

    @Schema(description = "大类（冗余，方便过滤）")
    private String category;

    @Schema(description = "产品型号编码")
    private String productCode;

    @Schema(description = "监控字段标识")
    private String monitorField;

    @Schema(description = "实际采集值（数值型变化点）")
    private BigDecimal actualValue;

    @Schema(description = "触发来源：MANUAL/IOT/MES/MDM/QMS/TPM/INTEGRATION")
    private String triggerSource;

    @Schema(description = "生产控制模式快照")
    private String controlMode;

    @Schema(description = "是否要求变化点首检")
    private Boolean inspectionRequired;

    @Schema(description = "是否要求独立验证")
    private Boolean verificationRequired;

    @Schema(description = "是否要求独立恢复生产审批（配置快照）")
    private Boolean productionReleaseRequired;

    @Schema(description = "恢复生产审批角色编码（配置快照）")
    private String productionReleaseRole;

    @Schema(description = "是否要求触发后自动暂停生产中的关联派工（配置快照）")
    private Boolean autoPauseRequired;

    @Schema(description = "关联派工是否已经处于暂停状态")
    private Boolean productionPaused;

    @Schema(description = "本事件是否实际执行了从生产中到暂停的状态转换")
    private Boolean pausedByChangePoint;

    @Schema(description = "最近一次暂停尝试永久请求号")
    private String pauseAttemptId;

    @Schema(description = "暂停结果：LOCAL_ONLY/MDM_CONFIRMED/NOT_RUNNING/ALREADY_PAUSED/FAILED_UNKNOWN/MDM_CONFIRMED_MES_FAILED")
    private String pauseOutcome;

    @Schema(description = "暂停执行结果说明")
    private String productionPauseResult;

    @Schema(description = "关联派工确认暂停时间")
    private LocalDateTime productionPauseTime;

    @Schema(description = "触发时未完成数量快照，仅作为在制暴露上限参考")
    private BigDecimal wipExposureQty;

    @Schema(description = "处置人现场确认的受影响在制数量")
    private BigDecimal affectedWipQty;

    @Schema(description = "受影响在制中判定放行数量")
    private BigDecimal releasedWipQty;

    @Schema(description = "受影响在制中判定返工数量")
    private BigDecimal reworkWipQty;

    @Schema(description = "受影响在制中判定报废数量")
    private BigDecimal scrapWipQty;

    @Schema(description = "本变化点确认损失金额")
    private BigDecimal lossAmount;

    @Schema(description = "主损失类型：SCRAP/REWORK/DOWNTIME/MATERIAL/QUALITY/OTHER")
    private String lossCategory;

    @Schema(description = "在制处置与损失测算说明")
    private String lossRemark;

    @Schema(description = "复产批准后是否进入加严巡检监控（配置快照）")
    private Boolean intensifiedInspectionRequired;

    @Schema(description = "加严巡检间隔分钟（配置快照）")
    private Integer intensifiedInspectionIntervalMin;

    @Schema(description = "退出加严监控所需连续合格次数（配置快照）")
    private Integer intensifiedInspectionPassCount;

    @Schema(description = "本轮加严巡检已连续合格次数")
    private Integer intensifiedInspectionPassedCount;

    @Schema(description = "本轮加严巡检已生成次数")
    private Integer intensifiedInspectionGeneratedCount;

    @Schema(description = "当前加严巡检MES生成记录ID")
    private Long intensifiedInspectionRecordId;

    @Schema(description = "当前加严巡检QMS任务ID")
    private Long intensifiedInspectionId;

    @Schema(description = "当前加严巡检质量门状态")
    private String intensifiedInspectionStatus;

    @Schema(description = "下次加严巡检触发时间")
    private LocalDateTime intensifiedInspectionNextTime;

    @Schema(description = "最近一次加严巡检处理时间")
    private LocalDateTime intensifiedInspectionLastTime;

    @Schema(description = "加严巡检监控开始时间")
    private LocalDateTime monitoringStartTime;

    @Schema(description = "加严巡检监控结束时间")
    private LocalDateTime monitoringEndTime;

    private Long inspectionRecordId;
    private Long inspectionId;
    private String inspectionStatus;

    private String assignedUserCodes;
    private String assignedUserNames;
    private String verifierUserCodes;
    private String verifierUserNames;
    private String releaseApproverUserCodes;
    private String releaseApproverUserNames;
    private String lastNotifyChannels;
    private String lastNotifyResult;

    private String acceptPersonCode;
    private String acceptPersonName;
    private LocalDateTime acceptTime;

    private LocalDateTime responseDeadline;
    private LocalDateTime handleDeadline;
    private LocalDateTime releaseDeadline;

    private Integer escalationCount;
    private LocalDateTime lastEscalationTime;
    private LocalDateTime nextEscalationTime;

    @Schema(description = "本次报警时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime alertTime;

    @Schema(description = "本次报警等级（字典）")
    private String alertLevel;

    @Schema(description = "是否已处理 0=否 1=是")
    private String handled;

    @Schema(description = "处理人")
    private String handleBy;

    /**
     * 通知用户
     */
    @Schema(description = "通知用户")
    private String noticePersonCode;

    /**
     * 通知用户姓名
     */
    @Schema(description = "通知用户姓名")
    private String noticePersonName;

    /**
     * 触发时间
     */
    @Schema(description = "触发时间")
    private LocalDateTime triggerTime;


    /**
     * 实际情况
     */
    @Schema(description = "实际情况")
    private String actualSituation;

    /**
     * 处理角色
     */
    @Schema(description = "验证角色快照，优先取等级规则")
    private String handleRole;

    /**
     * 处理人员
     */
    @Schema(description = "处理人员")
    private String handlePersonCode;

    /**
     * 处理人员姓名
     */
    @Schema(description = "处理人员姓名")
    private String handlePersonName;

    /**
     * 解决时间
     */
    @Schema(description = "解决时间")
    private LocalDateTime handleTime;

    /**
     * 解决说明
     */
    @Schema(description = "解决说明")
    private String handleDesc;

    private String rootCause;
    private String correctiveAction;
    private String preventiveAction;
    private LocalDateTime expectedCloseTime;

    private String verificationResult;
    private String verificationRemark;
    private String verifierCode;
    private String verifierName;
    private LocalDateTime verificationTime;

    @Schema(description = "恢复生产审批结论：APPROVE/REJECT")
    private String releaseDecision;

    @Schema(description = "恢复生产审批说明")
    private String releaseRemark;

    @Schema(description = "恢复生产审批人工号")
    private String releaseApproverCode;

    @Schema(description = "恢复生产审批人姓名")
    private String releaseApproverName;

    @Schema(description = "恢复生产审批时间")
    private LocalDateTime releaseTime;

    @Schema(description = "审批人确认的现场、设备/参数、物料质量和人员标准检查项")
    private String releaseChecks;

    @Schema(description = "验证通过时冻结的恢复生产证据快照")
    private String releaseEvidenceSnapshot;

    @Schema(description = "恢复生产证据SHA-256指纹")
    private String releaseEvidenceFingerprint;

    private LocalDateTime closeTime;
    private String closeReason;

    /**
     * 状态 待解决、已完成
     */
    @Schema(description = "状态 待解决、已完成")
    private String status;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "列表追溯开始时间，按触发时间包含")
    private LocalDateTime queryStartTime;

    @TableField(exist = false)
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "列表追溯结束时间，按触发时间不包含")
    private LocalDateTime queryEndTime;

    @TableField(exist = false)
    @Schema(description = "是否只查询已经完成现场在制核清的事件")
    private Boolean wipClearedOnly;

}
