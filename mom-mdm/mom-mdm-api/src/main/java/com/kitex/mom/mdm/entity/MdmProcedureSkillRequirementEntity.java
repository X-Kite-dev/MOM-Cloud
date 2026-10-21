package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TenantTable
@TableName("mdm_procedure_skill_requirement")
@EqualsAndHashCode(callSuper = true)
public class MdmProcedureSkillRequirementEntity extends BaseEntity<MdmProcedureSkillRequirementEntity> {
    private String processCode;
    private String processName;
    private String skillCode;
    private String skillName;
    private Integer minimumLevel;
    private Boolean certificateRequired;
    private Integer requiredCount;
    private Boolean status;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
