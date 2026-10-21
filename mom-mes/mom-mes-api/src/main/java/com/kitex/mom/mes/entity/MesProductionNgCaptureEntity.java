package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 首次生产不良的永久实物隔离采集账；不改原NG声明，不以隔离代表质量放行。
 */
@Data
@TenantTable
@TableName("mes_production_ng_capture")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "生产不良实物隔离采集原账")
public class MesProductionNgCaptureEntity extends BaseEntity<MesProductionNgCaptureEntity> {
    /**
     * 实际授权工厂编码。
     */
    @Schema(description = "实际授权工厂编码")
    private String factoryCode;

    /**
     * 本次生产隔离采集永久请求号。
     */
    @Schema(description = "本次生产隔离采集永久请求号")
    private String requestId;

    /**
     * 首次实际认证操作人主键。
     */
    @Schema(description = "首次实际认证操作人主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long actorId;

    /**
     * 真实首次不良报告，不是合格报工来源。
     */
    @Schema(description = "真实首次不良报告，不是合格报工来源")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceUnqualifiedReportId;

    /**
     * 原始隔离根库存主键。
     */
    @Schema(description = "原始隔离根库存主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long stockId;

    /**
     * 原始实际入库流水主键。
     */
    @Schema(description = "原始实际入库流水主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long inflowId;

    /**
     * 独立生产不良隔离案件主键。
     */
    @Schema(description = "独立生产不良隔离案件主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long caseId;

    /**
     * 原报工对象模式，冻结后不取当前模板。
     */
    @Schema(description = "原报工对象模式，冻结后不取当前模板")
    private String reportMode;

    /**
     * 首次实际执行入口，由服务端路由指定；历史NULL不根据当前策略补造。
     */
    @Schema(description = "实际执行入口STATION/MOBILE；历史为NULL")
    private String executionChannel;

    /**
     * 整件、整卡或整包约束。
     */
    @Schema(description = "整件、整卡或整包约束")
    private Boolean indivisible;

    /**
     * 原基本单位不良实物数量。
     */
    @Schema(description = "原基本单位不良实物数量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal quantity;

    /**
     * 首次原计量单位。
     */
    @Schema(description = "首次原计量单位")
    private String unit;

    /**
     * 原不良声明或实际FEED耗用选择。
     */
    @Schema(description = "原不良声明或实际FEED耗用选择")
    private String materialAccountingMode;

    /**
     * 采集请求协议版本。
     */
    @Schema(description = "采集请求协议版本")
    private String commandVersion;

    /**
     * 完整请求原字符串。
     */
    @Schema(description = "完整请求原字符串")
    private String commandSnapshot;

    /**
     * 请求版本域SHA256。
     */
    @Schema(description = "请求版本域SHA256")
    private String commandFingerprint;

    /**
     * 服务器实际采集证据版本。
     */
    @Schema(description = "服务器实际采集证据版本")
    private String evidenceVersion;

    /**
     * 原计量、库存、流水、案件和库位完整冻结证据。
     */
    @Schema(description = "原计量、库存、流水、案件和库位完整冻结证据")
    private String evidenceSnapshot;

    /**
     * 采集证据版本域SHA256。
     */
    @Schema(description = "采集证据版本域SHA256")
    private String evidenceFingerprint;

    /**
     * 固定ISOLATED；处置进度从永久案件回执核账。
     */
    @Schema(description = "固定ISOLATED；处置进度从永久案件回执核账")
    private String status;

}
