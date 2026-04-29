package com.kitex.mom.common.barcode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gitee.sunchenbin.mybatis.actable.annotation.Column;
import com.gitee.sunchenbin.mybatis.actable.annotation.Table;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 打印模板
 *
 * @author kitex
 * @date 2025-08-14 11:09:06
 */
@Data
@TenantTable
@TableName("print_template_business_type")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "打印模板业务类型管理")
@Table(comment = "打印模板业务类型管理")
public class PrintTemplateBusinessTypeEntity extends BaseEntity<PrintTemplateBusinessTypeEntity> {

    /**
     * 业务类型编码
     */
    @Column(comment = "业务类型编码")
    @Schema(description = "业务类型编码")
    private String code;

    /**
     * 业务类型名称
     */
    @Column(comment = "业务类型名称")
    @Schema(description = "业务类型名称")
    private String name;

    /**
     * 状态
     */
    @Column(comment = "状态")
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
