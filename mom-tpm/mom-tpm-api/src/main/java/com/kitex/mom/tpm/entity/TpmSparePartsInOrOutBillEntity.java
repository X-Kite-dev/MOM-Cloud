package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 备件 入/出库单
 *
 * @author kitex
 * @date 2025-02-11 16:42:13
 */
@Data
@TenantTable
@TableName("tpm_spare_parts_in_or_out_bill")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "备件 入/出库单")
public class TpmSparePartsInOrOutBillEntity extends BaseEntity<TpmSparePartsInOrOutBillEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 备件编码
     */
    @Schema(description = "备件编码")
    private String sparePartsCode;

    /**
     * 备件名称
     */
    @Schema(description = "备件名称")
    private String sparePartsName;

    /**
     * 备件规格
     */
    @Schema(description = "备件规格")
    private String sparePartsSpec;

    /**
     * 备件单位
     */
    @Schema(description = "备件单位")
    private String sparePartsUnit;

    /**
     * 库房编码
     */
    @Schema(description = "库房编码")
    private String storeroomCode;

    /**
     * 库房名称
     */
    @Schema(description = "库房名称")
    private String storeroomName;

    /**
     * 库位编码
     */
    @Schema(description = "库位编码")
    private String locationCode;

    /**
     * 库位名称
     */
    @Schema(description = "库位名称")
    private String locationName;

    /**
     * 操作数量
     */
    @Schema(description = "操作数量")
    private BigDecimal operateNum;

    /**
     * 操作类型 IN 入库 OUT 出库
     */
    @Schema(description = "操作类型 IN 入库 OUT 出库")
    private String operateType;

    /**
     * 使用途径
     */
    @Schema(description = "使用途径")
    private String operateName;

    /**
     * 状态 0 新建 1 已审批 2 已作废
     */
    @Schema(description = "状态 0 新建 1 已审批 2 已作废")
    private String state;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}