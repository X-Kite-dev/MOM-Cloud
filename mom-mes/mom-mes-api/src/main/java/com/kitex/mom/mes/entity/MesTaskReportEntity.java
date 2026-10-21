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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 生产报工 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-05-05
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "生产报工")
@TableName("mes_task_report")
public class MesTaskReportEntity extends BaseEntity<MesTaskReportEntity> {

    /**
     * 报工请求唯一号
     */
    @Schema(description = "报工请求唯一号")
    private String requestId;

    /**
     * EFFECTIVE-有效，REVERSED-已冲销
     */
    @Schema(description = "报工状态：EFFECTIVE-有效，REVERSED-已冲销")
    private String status;

    @Schema(description = "冲销请求唯一号")
    private String reverseRequestId;

    @Schema(description = "冲销原因")
    private String reverseReason;

    @Schema(description = "冲销时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
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
     * 报工数量
     */
    @Schema(description = "报工数量")
    private BigDecimal reportNum;

    @Schema(description = "受控数量纠正后的当前有效报工数量；新版报工等于原报工数量")
    private BigDecimal effectiveReportNum;

    @Schema(description = "已批准数量纠正累计数量；原报工数量=有效数量+累计纠正数量")
    private BigDecimal correctedReportNum;

    @Schema(description = "最近一次数量纠正时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastCorrectionTime;

    @Schema(description = "本次报工使用的受控重报许可ID")
    private Long correctionPermitId;

    @Schema(description = "本次报工使用的受控重报许可号")
    private String correctionPermitNo;

    @Schema(description = "本次报工实际使用的基本单位；历史未冻结保持空，不用当前主数据补造")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String reportUnit;

    @Schema(description = "本次实际执行的报工模式，历史未冻结保持空")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String reportMode;


    /**
     * 报工时间
     */
    @Schema(description = "报工时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reportTime;

    /**
     * 数据库时钟采集的本次报工接受时刻；与设备上报的事件时间分离。
     */
    @Schema(description = "服务器报工接受时刻，历史缺失不补造")
    private LocalDateTime timingAcceptedAt;

    /**
     * 当次全部时间规则、来源事实与窗口判定，只保存成功接受的真实证据。
     */
    @Schema(description = "跨工序时间校验证据JSON")
    private String timingEvidenceJson;

    /**
     * 条码/流转码
     */
    @Schema(description = "条码/流转码")
    private String transferCode;

    @Schema(description = "现场原始扫描标识；未转换时与规范标识相同")
    private String submittedIdentifier;

    @Schema(description = "命中的标识映射ID；直接使用规范码时为空")
    private Long identifierMappingId;

    @Schema(description = "本次使用的标识类型")
    private String identifierType;

    @Schema(description = "标识解析不可变快照")
    private String identifierResolutionSnapshot;

    /**
     * 动态外码具体占用实例；不混用静态别名映射ID。
     */
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @Schema(description = "本次使用的动态外码实例ID文本")
    private Long identifierBindingId;

    /**
     * 本次动作执行前后使用的占用生命周期版本，USE不递增。
     */
    @Schema(description = "本次实际使用的外码实例生命周期版本")
    private Integer identifierBindingVersion;

    /**
     * 从原派工契约和永久组核定的BIND/USE/RELEASE，不接受终端自报动作。
     */
    @Schema(description = "本次真实工艺节点动作；非动态报工为空")
    private String identifierNodeAction;

    /**
     * 实例、永久组、原扫描与生命周期的不可变核定依据。
     */
    @Schema(description = "动态外码解析和节点动作不可变快照JSON")
    private String identifierBindingSnapshot;

    @Schema(description = "行业报工扩展校验证据快照")
    private String extensionEvidenceSnapshot;

    @Schema(description = "执行种类：NORMAL-正常生产，ROUTE_RETURN-逐标识退回上序重过站")
    private String executionKind;

    @Schema(description = "退回上序申请ID；正常生产为空")
    private Long routeReturnId;

    @Schema(description = "逐标识路线版本；正常生产默认为1")
    private Integer identifierRouteVersion;

    @Schema(description = "载具逐件展开操作ID；普通单件报工为空")
    private Long carrierOperationId;

    @Schema(description = "执行本次逐件报工时所在的生产载具编码")
    private String carrierCode;

    /**
     * 类型 手动 自动
     */
    @Schema(description = "类型 手动 自动")
    private String type;

    @Schema(description = "自动报工来源事件号；人工报工为空")
    private String sourceEventId;

    @Schema(description = "设备事件发生时间；人工报工为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime sourceEventTime;

    @Schema(description = "自动工位人工接管报工时的原因")
    private String manualOverrideReason;

    @Schema(description = "报工时质量门模式")
    private String qualityGateMode;

    @Schema(description = "报工时质量门状态 PASSED/WARN/NONE")
    private String qualityGateStatus;

    @Schema(description = "报工时质量门判定说明")
    private String qualityGateMessage;

    @Schema(description = "报工或质量同步时实际参与判定的QMS任务ID JSON数组")
    private String qualityTaskIds;

    @Schema(description = "参与判定的QMS任务证据SHA-256指纹")
    private String qualityEvidenceFingerprint;

    @Schema(description = "参与判定的QMS任务不可变业务快照")
    private String qualityEvidenceSnapshot;

    @Schema(description = "质量数量状态 NOT_REQUIRED/PENDING/PARTIAL/RELEASED/REJECTED")
    private String qualityQuantityStatus;

    @Schema(description = "本次报工已质量放行数量")
    private BigDecimal qualityReleasedNum;

    @Schema(description = "本次报工待判数量")
    private BigDecimal qualityPendingNum;

    @Schema(description = "本次报工质量拒绝数量")
    private BigDecimal qualityRejectedNum;

    @Schema(description = "质量数量最近同步时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime qualitySyncTime;

    @Schema(description = "报工时设备运行门阶段")
    private String deviceGateStage;

    @Schema(description = "报工时设备运行门判定结果")
    private Boolean deviceGateAllowed;

    @Schema(description = "报工时设备状态编码")
    private String deviceGateState;

    @Schema(description = "报工时设备状态名称")
    private String deviceGateStateName;

    @Schema(description = "报工时设备运行门判定说明")
    private String deviceGateMessage;

    @Schema(description = "报工时设备运行门检查时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deviceGateCheckTime;

    /**
     * 设备编码
     */
    @Schema(description = "设备编码")
    private String deviceCode;

    /**
     * 设备名称
     */
    @Schema(description = "设备名称")
    private String deviceName;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
