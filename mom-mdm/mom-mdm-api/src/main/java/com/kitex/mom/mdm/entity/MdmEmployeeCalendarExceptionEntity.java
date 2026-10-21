package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@TenantTable
@TableName("mdm_employee_calendar_exception")
@EqualsAndHashCode(callSuper = true)
public class MdmEmployeeCalendarExceptionEntity extends BaseEntity<MdmEmployeeCalendarExceptionEntity> {
    private String employeeCode;
    private String employeeName;
    private LocalDate calendarDate;
    /**
     * OFF、LEAVE、TRAINING、OVERTIME
     */
    private String exceptionType;
    private LocalTime startTime;
    private LocalTime endTime;
    private String reason;
    private Boolean status;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
