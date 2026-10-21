package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 返工报工对原动态外码实例的一次永久引用。
 *
 * <p>该记录不改变原绑定的ACTIVE/RELEASED状态，也不把外码所有权迁移到返工工单；
 * GOOD和NG分别绑定真实报告，原实例发生版本变化后旧批准范围立即失效。</p>
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("mes_rework_identifier_usage")
@Schema(description = "返工原动态实例使用账")
public class MesReworkIdentifierUsageEntity extends BaseEntity<MesReworkIdentifierUsageEntity> {
    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reworkTaskId;
    private String reworkTaskCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceTaskId;
    private String sourceTaskCode;
    private String sourceProduceTaskCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceBindingId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceBindingGroupId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceOperationId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceReportId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long goodReportId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ngReportId;
    private String outcome;
    private String requestId;
    private String submittedIdentifier;
    private String canonicalIdentifier;
    private String inputMode;
    private Integer sourceBindingVersion;
    private String scopeFingerprint;
    private String evidenceSnapshot;
    private String evidenceFingerprint;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long actorId;
    private String actorCode;
    private LocalDateTime operationTime;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() { return super.getId(); }

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() { return super.getTenantId(); }
}
