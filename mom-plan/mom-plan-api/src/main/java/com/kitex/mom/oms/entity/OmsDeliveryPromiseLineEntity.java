package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 交期答复候选行；同时保留来源行当时值和拟答复值，禁止后续覆盖历史。 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("oms_delivery_promise_line")
@Schema(description = "交期答复候选行")
public class OmsDeliveryPromiseLineEntity extends BaseEntity<OmsDeliveryPromiseLineEntity> {
    @Schema(description = "交期答复主单ID")
    private Long promiseId;
    @Schema(description = "候选行序号")
    private Integer lineNo;
    @Schema(description = "原订单交付行ID；拆分新增行可为空")
    private Long sourceDetailId;
    @Schema(description = "产品编码")
    private String productCode;
    @Schema(description = "产品名称")
    private String productName;
    @Schema(description = "产品规格")
    private String productSpec;
    @Schema(description = "产品单位")
    private String productUnit;
    @Schema(description = "提交时原订单交期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate currentDeliverTime;
    @Schema(description = "提交时原订单数量")
    private BigDecimal currentDeliverNum;
    @Schema(description = "拟答复交期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate proposedDeliverTime;
    @Schema(description = "拟答复数量")
    private BigDecimal proposedDeliverNum;
    @Schema(description = "候选行说明")
    private String remark;
    @Schema(description = "所属工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
