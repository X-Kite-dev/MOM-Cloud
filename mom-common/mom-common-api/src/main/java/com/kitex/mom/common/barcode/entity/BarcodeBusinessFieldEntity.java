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
 * 条码业务属性
 */
@Data
@TenantTable
@TableName("barcode_business_field")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "条码业务属性")
@Table(comment = "条码业务属性")
public class BarcodeBusinessFieldEntity extends BaseEntity<BarcodeBusinessFieldEntity> {

    @Schema(description = "条码规则ID")
    @Column(comment = "条码规则ID")
    private Long barcodeRuleId;

    @Schema(description = "业务名称")
    @Column(comment = "业务名称")
    private String name;

    @Schema(description = "业务标识")
    @Column(comment = "业务标识")
    private String value;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @Column(comment = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
