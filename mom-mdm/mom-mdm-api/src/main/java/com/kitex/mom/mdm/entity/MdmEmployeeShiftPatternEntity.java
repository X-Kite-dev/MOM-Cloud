package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@TenantTable
@TableName("mdm_employee_shift_pattern")
@EqualsAndHashCode(callSuper = true)
public class MdmEmployeeShiftPatternEntity extends BaseEntity<MdmEmployeeShiftPatternEntity> {
    private String employeeCode;
    private String employeeName;
    private Integer dayOfWeek;
    private String shiftCode;
    private String shiftName;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Boolean status;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
