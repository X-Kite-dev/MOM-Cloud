package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TenantTable
@TableName("mdm_skill")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "人员技能字典")
public class MdmSkillEntity extends BaseEntity<MdmSkillEntity> {
    private String skillCode;
    private String skillName;
    private String skillCategory;
    private String description;
    private Boolean certificateRequired;
    private Boolean status;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
