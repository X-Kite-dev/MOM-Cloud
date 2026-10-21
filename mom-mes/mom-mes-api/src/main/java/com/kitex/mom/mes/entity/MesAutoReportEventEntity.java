package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 自动报工原始事件、处理结论和补偿审计。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_auto_report_event")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备自动报工事件台账")
public class MesAutoReportEventEntity extends BaseEntity<MesAutoReportEventEntity> {
    /**
     * 来源事件号在租户内永久唯一，失败也不能换正文重投。
     */
    @Schema(description = "永久来源事件号")
    private String sourceEventId;
    @Schema(description = "原事件设备编码")
    private String deviceCode;
    @Schema(description = "原设备事件标识")
    private String eventIdentifier;
    @Schema(description = "来源发生时间，不是服务器处理时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime sourceEventTime;
    @Schema(description = "原设备计数或脉冲值")
    private BigDecimal rawValue;
    @Schema(description = "成功报工的实际折算量")
    private BigDecimal resolvedQuantity;
    @Schema(description = "来源原指定派工；动态节点必须明确")
    private String requestedArrangeCode;
    @Schema(description = "原始现场扫描值，不以当前外码重新解释")
    private String transferCode;
    @Schema(description = "实际处理采用的规则ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ruleId;
    @Schema(description = "实际处理规则编码")
    private String ruleCode;
    @Schema(description = "成功归属派工")
    private String arrangeCode;
    @Schema(description = "原成功报工ID，文本序列化")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reportId;
    /**
     * RECEIVED / SUCCESS / IGNORED / FAILED。
     */
    private String status;
    @Schema(description = "处理结论或失败原因")
    private String processMessage;
    @Schema(description = "补偿重试次数")
    private Integer retryCount;
    @Schema(description = "原补偿重试原因")
    private String retryReason;
    @Schema(description = "补偿操作者账号")
    private String retryBy;
    @Schema(description = "最后一次处理时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastProcessTime;
    @Schema(description = "仅审计的原报文，不从中推导动态实例")
    private String rawPayload;
    /**
     * 成功报工采用的规则、基线及换算证据；非当前配置。
     */
    private String conversionSnapshot;
    /**
     * 以下采集事实首次入账后不可变；旧记录为null，不倒推补造。
     */
    @Schema(description = "原事件采集版本；旧记录为空")
    private String captureVersion;
    @Schema(description = "绑定节点原第二码")
    private String externalIdentifier;
    @Schema(description = "原采集实例ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long expectedBindingId;
    @Schema(description = "原采集实例生命周期版本")
    private Integer expectedBindingVersion;
    @Schema(description = "已认证的原采集账号ID，不能以设备码代替")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long captureActorId;
    @Schema(description = "原采集实名账号编码")
    private String captureActorCode;
    @Schema(description = "采集时唯一核定的规则ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long captureRuleId;
    @Schema(description = "原单件换算规则全文，不含当前计数状态")
    private String captureRuleSnapshot;
    @Schema(description = "原派工完整冻结节点策略全文")
    private String capturePolicySnapshot;
    @Schema(description = "原核定内部规范SN")
    private String captureCanonicalIdentifier;
    @Schema(description = "版本化原采集全文，包含原输入、实名、规则和节点")
    private String captureSnapshot;
    @Schema(description = "版本化原采集全文SHA-256摘要")
    private String captureFingerprint;
    @Schema(description = "实际归属工厂")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
