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
 * 生产报工-不合格品上报 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-05-05
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "生产报工-不合格品上报")
@TableName("mes_task_report_unqualified")
public class MesTaskReportUnqualifiedEntity extends BaseEntity<MesTaskReportUnqualifiedEntity> {

    /**
     * 不合格上报请求唯一号
     */
    @Schema(description = "不合格上报请求唯一号")
    private String requestId;

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
     * 不合格数量
     */
    @Schema(description = "不合格数量")
    private BigDecimal unqualifiedNum;

    /**
     * 本次实际不良报工的原基本计量单位；升级前历史不从当前物料补造。
     */
    @Schema(description = "不良报工原计量单位，历史未证明时为空")
    private String reportUnit;

    /**
     * 本次真实派工解析后的规范报工模式，不是库存或处置模式。
     */
    @Schema(description = "不良报工原模式")
    private String reportMode;

    /**
     * 不良生产来源的独立协议版本，不复用合格报工或实物转换来源版本。
     */
    @Schema(description = "不良生产来源版本")
    private String sourceVersion;

    /**
     * 实际不良报告、身份、派工、物料单位和原契约的完整冻结字符串。
     */
    @Schema(description = "不良生产来源原证据")
    private String sourceSnapshot;

    /**
     * 原版本加竖线加原字符串的UTF-8 SHA-256，不能用当前主数据重算证据。
     */
    @Schema(description = "不良生产来源原证据SHA-256")
    private String sourceFingerprint;

    /**
     * 当次真实认证用户主键；不能由页面指定或用员工显示姓名充当身份。
     */
    @Schema(description = "原采集实名用户ID")
    private Long capturedActorId;

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

    /** 操作者原始输入；规范码独立保存在transferCode，原号恢复不重新解析今日映射。 */
    @Schema(description = "原始扫描标识，旧历史未留存时为空")
    private String submittedIdentifier;

    /** 本次真实使用的静态映射主键；直接规范码或无码数量报工为空。 */
    @Schema(description = "本次使用的静态标识映射ID")
    private Long identifierMappingId;

    /** 当次解析类型；不根据今日映射推断历史类型。 */
    @Schema(description = "当次标识类型，直接规范码为CANONICAL")
    private String identifierType;

    /** 共用静态解析器的完整原字符串，包含当时映射版本和作用域。 */
    @Schema(description = "本次静态标识解析原证据")
    private String identifierResolutionSnapshot;

    /** 独立于固定SOURCE_V1的双标识证据版本；历史保持NULL。 */
    @Schema(description = "不良双标识证据版本")
    private String identificationVersion;

    /** 绑定原报告、原始输入、规范码与SOURCE_V1指纹的完整12字段证据。 */
    @Schema(description = "不良双标识原证据")
    private String identificationSnapshot;

    /** 独立版本加竖线加原证据字符串的UTF-8 SHA-256。 */
    @Schema(description = "不良双标识原证据SHA-256")
    private String identificationFingerprint;

    /**
     * 不合格类型
     */
    @Schema(description = "不合格类型")
    private String type;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
