package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * <p>
 * 生产报工-操作明细 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-05-05
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "生产报工-操作明细")
@TableName("mes_task_report_record")
public class MesTaskReportRecordEntity extends BaseEntity<MesTaskReportRecordEntity> {

    @Schema(description = "关联报工记录ID")
    private Long reportId;

    /**
     * 从当前原报告按租户/工厂补充，不回写历史操作流水。
     */
    @TableField(exist = false)
    @Schema(description = "原报告服务器接受时刻")
    private LocalDateTime timingAcceptedAt;

    /**
     * 从原报告读取的不可变规则与来源证据；无证据不推断历史通过。
     */
    @TableField(exist = false)
    @Schema(description = "原报告跨工序时间校验证据")
    private String timingEvidenceJson;

    @Schema(description = "载具逐件展开操作ID")
    private Long carrierOperationId;

    @Schema(description = "执行时所在的生产载具编码")
    private String carrierCode;

    @Schema(description = "本次报工合格数量")
    private BigDecimal reportNum;

    @Schema(description = "该操作完成后的当前有效报工数量")
    private BigDecimal effectiveReportNum;

    @Schema(description = "该操作完成后的累计纠正数量")
    private BigDecimal correctedReportNum;

    @Schema(description = "最近一次数量纠正时间")
    private LocalDateTime lastCorrectionTime;

    @Schema(description = "本次报工使用的受控重报许可ID")
    private Long correctionPermitId;

    @Schema(description = "本次报工使用的受控重报许可号")
    private String correctionPermitNo;

    @Schema(description = "报工状态：EFFECTIVE-有效，REVERSED-已冲销")
    private String status;

    @Schema(description = "冲销原因")
    private String reverseReason;

    @Schema(description = "冲销时间")
    private LocalDateTime reverseTime;

    @Schema(description = "冲销人工号")
    private String reverseWorkerCode;

    @Schema(description = "冲销人姓名")
    private String reverseWorkerName;

    /**
     * 派工单号
     */
    @Schema(description = "派工单号")
    private String arrangeCode;

    /**
     * 任务单号
     */
    @Schema(description = "任务单号")
    private String produceTaskCode;

    /**
     * 生产工单号
     */
    @Schema(description = "生产工单号")
    private String taskCode;

    /**
     * 员工工号
     */
    @Schema(description = "员工工号")
    private String workerCode;

    /**
     * 员工姓名
     */
    @Schema(description = "员工姓名")
    private String workerName;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 报工时间
     */
    @Schema(description = "报工时间")
    private LocalDateTime reportTime;

    /**
     * 操作类型
     */
    @Schema(description = "操作类型")
    private String type;

    @Schema(description = "报工触发方式：MANUAL人工、AUTO设备自动")
    private String triggerType;

    @Schema(description = "自动报工来源事件号")
    private String sourceEventId;

    @Schema(description = "设备事件发生时间")
    private LocalDateTime sourceEventTime;

    @Schema(description = "自动工位人工接管原因")
    private String manualOverrideReason;

    @Schema(description = "业务发生时的质量门模式")
    private String qualityGateMode;

    @Schema(description = "业务发生时的质量门状态")
    private String qualityGateStatus;

    @Schema(description = "业务发生时的质量门判定说明")
    private String qualityGateMessage;

    @Schema(description = "业务发生时参与判定的QMS任务ID JSON数组")
    private String qualityTaskIds;

    @Schema(description = "业务发生时QMS判定证据SHA-256指纹")
    private String qualityEvidenceFingerprint;

    @Schema(description = "业务发生时QMS判定证据快照")
    private String qualityEvidenceSnapshot;

    @Schema(description = "质量数量状态")
    private String qualityQuantityStatus;

    @Schema(description = "已质量放行数量")
    private BigDecimal qualityReleasedNum;

    @Schema(description = "待判数量")
    private BigDecimal qualityPendingNum;

    @Schema(description = "质量拒绝数量")
    private BigDecimal qualityRejectedNum;

    @Schema(description = "业务发生时设备运行门阶段")
    private String deviceGateStage;

    @Schema(description = "业务发生时设备运行门判定结果")
    private Boolean deviceGateAllowed;

    @Schema(description = "业务发生时设备状态编码")
    private String deviceGateState;

    @Schema(description = "业务发生时设备状态名称")
    private String deviceGateStateName;

    @Schema(description = "业务发生时设备运行门判定说明")
    private String deviceGateMessage;

    @Schema(description = "业务发生时设备运行门检查时间")
    private LocalDateTime deviceGateCheckTime;

    @Schema(description = "设备编码")
    private String deviceCode;

    /**
     * 操作明细
     */
    @Schema(description = "操作明细")
    private String content;

    /**
     * 流转码/条码/箱码
     */
    @Schema(description = "流转码/条码/箱码")
    private String transferCode;

    @Schema(description = "现场提交的原始生产标识")
    private String submittedIdentifier;

    @Schema(description = "命中的标识映射版本ID")
    private Long identifierMappingId;

    @Schema(description = "本次使用的生产标识类型")
    private String identifierType;

    @Schema(description = "生产标识解析不可变快照")
    private String identifierResolutionSnapshot;

    /**
     * 只读返回原主报工的精确动态实例文本，不向历史流水回填或新增持久列。
     */
    @TableField(exist = false)
    @Schema(description = "原动态占用实例ID文本", accessMode = Schema.AccessMode.READ_ONLY)
    private String identifierBindingId;

    /**
     * 原主报工核定的动作，非当前模板的动作。
     */
    @TableField(exist = false)
    @Schema(description = "原节点动作", accessMode = Schema.AccessMode.READ_ONLY)
    private String identifierNodeAction;

    /**
     * 原主报工采集的生命周期版本，不能用当前实例版本覆盖。
     */
    @TableField(exist = false)
    @Schema(description = "原节点生命周期版本", accessMode = Schema.AccessMode.READ_ONLY)
    private Integer identifierBindingVersion;

    @Schema(description = "执行种类：NORMAL或ROUTE_RETURN")
    private String executionKind;

    @Schema(description = "退回上序申请ID")
    private Long routeReturnId;

    @Schema(description = "逐标识路线版本")
    private Integer identifierRouteVersion;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
