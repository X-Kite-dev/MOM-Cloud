package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 订单交付信息表
 *
 * @author kitex
 * @date 2025-01-07 14:38:56
 */
@Data
@TableName("oms_order_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "订单交付信息表")
public class OmsOrderDetailsEntity extends Model<OmsOrderDetailsEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 所属订单
     */
    @Schema(description = "所属订单")
    private String orderCode;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String productSpec;

    /**
     * 产品单位
     */
    @Schema(description = "产品单位")
    private String productUnit;

    /**
     * 交付时间
     */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @Schema(description = "交付时间")
    private LocalDate deliverTime;

    /**
     * 交付数量
     */
    @Schema(description = "交付数量")
    private BigDecimal deliverNum;

    /**
     * 已交付数量
     */
    @Schema(description = "已交付数量")
    private BigDecimal deliveredNum;

    /** 客户签收累计量；与仓库出库/退货净交付量分账。 */
    @Schema(description = "客户签收累计量")
    private BigDecimal customerSignedNum;

    /** 客户拒收、破损、丢失累计量；等待退货、补发或索赔闭环。 */
    @Schema(description = "客户交付异常累计量")
    private BigDecimal customerExceptionNum;

    /** 已由补发签收、退款或让步结案冲销的客户异常累计量。 */
    @Schema(description = "客户交付异常已解决累计量")
    private BigDecimal customerResolvedExceptionNum;

    /** 退款或商业让步已结案的异常量；用于与补发签收区分订单完成口径。 */
    @Schema(description = "客户异常结算关闭累计量")
    private BigDecimal customerSettlementResolvedNum;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private String state;

    /**
     * 是否变更中 0否  1是
     */
    @Schema(description = "是否变更中")
    private String changeSign;

    @TableField(exist = false)
    private BigDecimal operateNum;

    @TableField(exist = false)
    @Schema(description = "已下发PLAN来源需求状态，供OMS变更界面解释可编辑边界")
    private String planDemandState;

    @TableField(exist = false)
    @Schema(description = "仅PLAN来源需求仍是无下游事实的未接收草案时允许订单行数量/交期同步变更")
    private Boolean planDemandEditable;

    @TableField(exist = false)
    private String planDemandBlockReason;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
