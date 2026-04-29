package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 班组员工关联
 *
 * @author kitex
 * @date 2025-01-20 10:00:00
 */
@Data
@TenantTable
@TableName("mdm_employee_team")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "班组员工关联")
public class MdmEmployeeTeamEntity extends BaseEntity<MdmEmployeeTeamEntity> {

    /**
     * 员工ID
     */
    @Schema(description = "员工ID")
    private Long employeeId;

    /**
     * 员工工号
     */
    @Schema(description = "员工工号")
    private String employeeCode;

    /**
     * 员工姓名
     */
    @Schema(description = "员工姓名")
    private String employeeName;

    /**
     * 班组ID
     */
    @Schema(description = "班组ID")
    private Long teamId;

    /**
     * 班组编码
     */
    @Schema(description = "班组编码")
    private String teamCode;

    /**
     * 班组名称
     */
    @Schema(description = "班组名称")
    private String teamName;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private Boolean status;
}