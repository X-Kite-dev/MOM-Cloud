package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 合同模板
 *
 * @author kitex
 * @date 2025-04-02 15:58:17
 */
@Data
@TenantTable
@TableName("pms_contract_template")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "合同模板")
public class PmsContractTemplateEntity extends BaseEntity<PmsContractTemplateEntity> {

    /**
     * 合同模版编码
     */
    @Schema(description = "合同模版编码")
    private String code;

    /**
     * 合同模板名称
     */
    @Schema(description = "合同模板名称")
    private String name;

    /**
     * 服务类型
     */
    @Schema(description = "服务类型")
    private String serviceType;

    /**
     * 合同类型
     */
    @Schema(description = "合同类型")
    private String contractType;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 合同模板文件
     */
    @Schema(description = "合同模板文件")
    private String file;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}