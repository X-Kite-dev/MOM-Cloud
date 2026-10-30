package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 备件出入库流水表
 *
 * @author kitex
 * @date 2025-02-11 16:44:34
 */
@Data
@TenantTable
@TableName("tpm_spare_parts_in_or_out_record")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "备件出入库流水表")
public class TpmSparePartsInOrOutRecordEntity extends BaseEntity<TpmSparePartsInOrOutRecordEntity> {

    /**
     * 维修领料或退回审批生成的不可变库存回执；历史普通流水为空。
     */
    @Schema(description = "维修备件库存回执ID")
    private Long repairSpareReceiptId;


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
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
