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
 * 条码核心表
 *
 * @author kitex
 * @date 2023-09-01 14:45:01
 */
@Data
@TenantTable
@TableName("barcode_rule")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "条码规则")
@Table(comment = "条码规则")
public class BarcodeRuleEntity extends BaseEntity<BarcodeRuleEntity> {

    /**
     * 条码名称
     */
    @Schema(description = "条码名称")
    @Column(comment = "条码名称")
    private String name;

    /**
     * 条码编码
     */
    @Schema(description = "条码编码")
    @Column(comment = "条码编码")
    private String code;

    /**
     * 校验正则
     */
    @Schema(description = "正则")
    @Column(comment = "正则")
    private String regular;

    /**
     * 插件路径
     */
    @Schema(description = "插件路径")
    @Column(comment = "插件路径")
    private String pluginPath;

    /**
     * 打印模板Code
     */
    @Schema(description = "打印模板Code")
    @Column(comment = "打印模板Code")
    private String printTemplateCode;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
