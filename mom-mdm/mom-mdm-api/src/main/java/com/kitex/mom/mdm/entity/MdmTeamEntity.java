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

import java.util.List;

/**
 * 班组
 *
 * @author kitex
 * @date 2025-02-17 08:50:00
 */
@Data
@TenantTable
@TableName("mdm_team")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "班组")
public class MdmTeamEntity extends BaseEntity<MdmTeamEntity> {

    /**
     * 班组名称
     */
    @Schema(description = "班组名称")
    private String name;

    /**
     * 班组编码
     */
    @Schema(description = "班组编码")
    private String code;

    /**
     * 类型
     */
    @Schema(description = "类型")
    private String type;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "teamId")
    private List<MdmEmployeeTeamEntity> mdmEmployeeTeamList;
}