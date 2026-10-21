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

@Data
@TenantTable
@TableName("mdm_material_authorization_log")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "物料授权全生命周期审计日志")
public class MdmMaterialAuthorizationLogEntity extends BaseEntity<MdmMaterialAuthorizationLogEntity> {
    private Long authorizationId;
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
