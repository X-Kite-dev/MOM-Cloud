package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 合同管理
 *
 * @author kitex
 * @date 2025-04-02 16:10:18
 */
@Data
@TableName("pms_contract_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "pms_contract_detail")
public class PmsContractDetailEntity extends Model<PmsContractDetailEntity> {

    /**
     * 合同产品表主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "合同产品表主键")
    private Long id;
    /**
     * 合同id
     */
    @Schema(description = "合同id")
    private Long contractId;
    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;
    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;
    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;
    /**
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;
    /**
     * 采购数量
     */
    @Schema(description = "采购数量")
    private Double orderNum;
    /**
     * 单价
     */
    @Schema(description = "单价")
    private BigDecimal price;
}