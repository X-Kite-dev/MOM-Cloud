package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TenantTable
@TableName("mes_rework_material_plan")
@EqualsAndHashCode(callSuper = true)
public class MesReworkMaterialPlanEntity extends BaseEntity<MesReworkMaterialPlanEntity> {
    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    private String taskCode;
    private String status;
    private String sourceFingerprint;
    private String sourceSnapshot;
    private String originalBomSnapshot;
    private String originalRouteSnapshot;
    private String resultBomSnapshot;
    private String resultRouteSnapshot;
    private String materialSnapshot;
    private Boolean noMaterialConfirmed;
    private String reason;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long applicantId;
    private String applicantCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reviewerId;
    private String reviewReason;
    private String planKind;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourcePlanId;
    private String supplementSnapshot;
    private String preparationSnapshot;
    /** 双岗批准的跨工单时间口径；INITIAL必填，追加沿用当前批准正文。 */
    private String reworkTimingPolicySnapshot;
    /** 首轮方案冻结的原动态实例全集；NONE也保存明确原文，不以空值表示无动态来源。 */
    private String identifierScopeSnapshot;
    /** identifierScopeSnapshot原始UTF-8字节SHA-256。 */
    private String identifierScopeFingerprint;
    /** INHERIT_SOURCE或APPROVED_OVERRIDE。 */
    private String reworkEngineeringMode;
    /** 选择的MDM返工覆盖版本，继承模式为空。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reworkEngineeringOverrideId;
    /** 覆盖版本原文SHA256。 */
    private String reworkEngineeringOverrideHash;
    /** 返工工程选择、原生产版本、节点及MDM覆盖包的不可变原文。 */
    private String reworkEngineeringScopeSnapshot;
    /** reworkEngineeringScopeSnapshot原始UTF-8字节SHA256。 */
    private String reworkEngineeringScopeFingerprint;
    /** 方案作用后的单节点参数标准身份及正文。 */
    private String resultParameterStandardNo;
    private Integer resultParameterStandardVersion;
    private String resultParameterStandardSnapshot;
    private String resultParameterSnapshotFingerprint;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
