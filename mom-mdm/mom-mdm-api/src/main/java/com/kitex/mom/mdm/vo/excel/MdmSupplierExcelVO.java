package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * @program: mom-cloud
 * @description: 供应商Excel数据类
 * @author: qinghuan
 * @create: 2024-10-26 19:38
 **/
@Data
@ColumnWidth(30)
@Schema(description = "供应商excel数据类")
public class MdmSupplierExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入回显行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    @ExcelProperty(value = "供应商编码")
    @NotBlank(message = "供应商编码不能为空")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    @ExcelProperty(value = "供应商名称")
    @NotBlank(message = "供应商名称不能为空")
    private String supplierName;

    /**
     * 供应商分类编码
     */
    @Schema(description = "供应商分类编码")
    @ExcelProperty(value = "供应商分类编码")
    private String supplierClassCode;

    /**
     * 供应商分类名称
     */
    @Schema(description = "供应商分类名称")
    @ExcelProperty(value = "供应商分类名称")
    @NotBlank(message = "供应商分类名称不能为空")
    private String supplierClassName;

    /**
     * 状态 (1=启用, 0=禁用)
     */
    @Schema(description = "状态 (1=启用, 0=禁用)")
    @ExcelProperty(value = "状态 (1=启用, 0=禁用)")
    private String status;

    /**
     * 供应商类型
     */
    @Schema(description = "供应商类型")
    @ExcelProperty(value = "供应商类型")
    private String supplierType;

    /**
     * 开户行
     */
    @Schema(description = "开户行")
    @ExcelProperty(value = "开户行")
    private String bank;

    /**
     * 收款账户
     */
    @Schema(description = "收款账户")
    @ExcelProperty(value = "收款账户")
    private String account;

    /**
     * 税种
     */
    @Schema(description = "税种")
    @ExcelProperty(value = "税种")
    private String taxType;

    /**
     * 税率
     */
    @Schema(description = "税率")
    @ExcelProperty(value = "税率")
    private BigDecimal taxRate;

    /**
     * 主要供应产品
     */
    @Schema(description = "主要供应产品")
    @ExcelProperty(value = "主要供应产品")
    private String mainProducts;

    /**
     * 详细地址
     */
    @Schema(description = "详细地址")
    @ExcelProperty(value = "详细地址")
    private String detailedAddress;

    /**
     * 交货提前期（天）
     */
    @Schema(description = "交货提前期（天）")
    @ExcelProperty(value = "交货提前期（天）")
    private Integer deliveryLeadTime;

    /**
     * 交货周期（天）
     */
    @Schema(description = "交货周期（天）")
    @ExcelProperty(value = "交货周期（天）")
    private Integer deliveryCycle;

    /**
     * 委外发料周期（天）
     */
    @Schema(description = "委外发料周期（天）")
    @ExcelProperty(value = "委外发料周期（天）")
    @ColumnWidth(50)
    private Integer outsourcingIssuanceCycle;

    /**
     * 月初委外发料周期（天）
     */
    @Schema(description = "月初委外发料周期（天）")
    @ExcelProperty(value = "月初委外发料周期（天）")
    @ColumnWidth(50)
    private Integer beginningOfMonthOutsourcingIssuanceCycle;

    /**
     * 交货方式
     */
    @Schema(description = "交货方式")
    @ExcelProperty(value = "交货方式")
    private String deliveryMethod;

}
