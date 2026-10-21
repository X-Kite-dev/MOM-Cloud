package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mdm_employee_skill")
@EqualsAndHashCode(callSuper = true)
public class MdmEmployeeSkillEntity extends BaseEntity<MdmEmployeeSkillEntity> {
    private String employeeCode;
    private String employeeName;
    private String skillCode;
    private String skillName;
    private Integer proficiencyLevel;
    private String certificateNo;
    private LocalDate validFrom;
    private LocalDate validTo;
    private Boolean status;
    /**
     * 资质被停用或从人员档案移除的时刻；重新启用后清空。
     */
    private LocalDateTime invalidTime;
    private String verifiedBy;
    private LocalDateTime verifiedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
