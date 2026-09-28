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
     * 采购订单明细主键。合同条款必须精确追溯到订单行，不能仅依赖物料编码猜测。
     */
    @Schema(description = "采购订单明细主键")
    private Long orderDetailId;

    /** 订单行对应的采购计划号，用于订单变更后回退计划占用。 */
    @Schema(description = "采购计划号")
    private String pmsPlanNo;
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

    /** 已通过受控变更撤回、不得继续收料的未到货数量；原合同数量永久保留。 */
    @Schema(description = "受控撤回数量")
    private BigDecimal cancelledNum;

    /** 最近一次执行合同条款供给撤回的采购订单变更申请。 */
    @Schema(description = "撤回申请ID")
    private Long cancellationRequestId;

    /** 合同条款商业数量变更版本。 */
    @Schema(description = "条款变更版本")
    private Integer changeVersion;
    /**
     * 单价
     */
    @Schema(description = "单价")
    private BigDecimal price;
}
