package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 生产批量策略编制、启用、暂停和退役的不可变操作履历。
 */
@Data
@TenantTable
@TableName("mdm_production_lot_policy_log")
@EqualsAndHashCode(callSuper = true)
@Schema(name = "MdmProductionLotPolicyLog", description = "生产批量策略生命周期审计履历")
public class MdmProductionLotPolicyLogEntity extends BaseEntity<MdmProductionLotPolicyLogEntity> {
    private Long policyId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private String reason;
    private String operatedBy;
    private String operatedByName;
    private LocalDateTime operatedTime;
    private String beforeSnapshot;
    private String afterSnapshot;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
