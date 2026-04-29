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
 * 条码校验记录
 *
 * @author kitex
 * @date 2023-09-01 14:45:49
 */
@Data
@TenantTable
@TableName("barcode_check_log")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "条码校验记录")
@Table(comment = "条码校验记录")
public class BarcodeCheckLogEntity extends BaseEntity<BarcodeCheckLogEntity> {

    /**
     * 结果
     */
    @Schema(description = "结果")
    @Column(comment = "结果")
    private String result;

    /**
     * 条码
     */
    @Schema(description = "条码")
    @Column(comment = "条码")
    private String barcode;

    /**
     * 条码编码
     */
    @Schema(description = "条码编码")
    @Column(comment = "条码编码")
    private String barcodeCode;

    /**
     * 条码名称
     */
    @Schema(description = "条码名称")
    @Column(comment = "条码名称")
    private String barcodeName;

    /**
     * 条码规则
     */
    @Schema(description = "条码规则")
    @Column(comment = "条码规则")
    private String barcodeRule;

    /**
     * 数据
     */
    @Schema(description = "数据")
    @Column(comment = "数据")
    private String jsonData;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
