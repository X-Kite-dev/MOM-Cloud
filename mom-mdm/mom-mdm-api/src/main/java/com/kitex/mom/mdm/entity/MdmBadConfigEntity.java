package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.mdm.base.BusinessEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 不良项管理
 *
 * @author kitex
 * @since 2025-05-17
 */
@Data
@TenantTable
@TableName("mdm_bad_config")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "不良项管理")
public class MdmBadConfigEntity extends BusinessEntity<MdmBadConfigEntity> {

    @Schema(description = "不良编码")
    private String badCode;

    @Schema(description = "不良名称")
    private String badName;

    @Schema(description = "不良类型 分类、不良品项")
    private String badType;

    @Schema(description = "父编码")
    private String parentCode;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "状态")
    private String status;


    /**
     * 子级
     */
    @TableField(exist = false)
    @Schema(description = "子级")
    private List<MdmBadConfigEntity> children;

    @TableField(exist = false)
    private Boolean disabled;

    @TableField(exist = false)
    private Boolean chooseClassify = false;
}
