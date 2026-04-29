package com.kitex.mom.mdm.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDate;
import java.util.List;

/**
 * 员工
 *
 * @author kitex
 * @date 2025-01-20 10:00:00
 */
@Data
@TenantTable
@TableName("mdm_employee")
@FieldNameConstants
@EqualsAndHashCode(callSuper = true)
@Schema(description = "员工")
public class MdmEmployeeEntity extends BaseEntity<MdmEmployeeEntity> {

    /**
     * 工号
     */
    @Schema(description = "工号")
    private String employeeCode;

    /**
     * 姓名
     */
    @Schema(description = "姓名")
    private String name;

    /**
     * 性别
     */
    @Schema(description = "性别")
    private String gender;

    /**
     * 入职时间
     */
    @Schema(description = "入职时间")
    private LocalDate hireDate;

    /**
     * 手机号
     */
    @Schema(description = "手机号")
    private String phone;

    /**
     * 邮箱
     */
    @Schema(description = "邮箱")
    private String email;

    /**
     * 身份证号
     */
    @Schema(description = "身份证号")
    private String idCard;

    /**
     * 部门
     */
    @Schema(description = "部门")
    private String department;

    /**
     * 职位
     */
    @Schema(description = "职位")
    private String position;

    /**
     * 状态(1 在职 0 离职)
     */
    @Schema(description = "状态(1 在职 0 离职)")
    private String status;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 是否同步至系统用户
     */
    @Schema(description = "是否同步至系统用户")
    private Boolean syncStatus;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "employeeId")
    private List<MdmEmployeeTeamEntity> mdmEmployeeTeamList;
}