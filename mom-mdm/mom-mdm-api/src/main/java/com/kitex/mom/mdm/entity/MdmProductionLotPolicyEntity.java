package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 产品生产批量策略的版本化主数据。
 *
 * <p>该策略与采购最小订购量、仓库发料批量相互独立，供MPS拆桶和APS批次形成使用。
 * 正式计划只读取处于有效期内的ACTIVE版本。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mdm_production_lot_policy")
@EqualsAndHashCode(callSuper = true)
@Schema(name = "MdmProductionLotPolicy", description = "产品最小、经济、最大生产批量及整批倍数策略")
public class MdmProductionLotPolicyEntity extends BaseEntity<MdmProductionLotPolicyEntity> {

    @Schema(description = "策略编号；与版本号组合在同一租户、工厂内唯一")
    private String policyNo;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称快照")
    private String productName;

    @Schema(description = "版本号，从1开始递增")
    private Integer versionNo;

    @Schema(description = "单个生产批次允许的最小数量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal minimumBatchQuantity;

    @Schema(description = "优先采用的经济生产批量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal economicBatchQuantity;

    @Schema(description = "单个生产批次允许的最大数量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal maximumBatchQuantity;

    @Schema(description = "整批倍数；每个批次数量必须是该数值的整数倍")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal batchMultiple;

    @Schema(description = "生效时间")
    private LocalDateTime validStartTime;

    @Schema(description = "失效时间；为空表示长期有效")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime validEndTime;

    @Schema(description = "状态：DRAFT、ACTIVE、SUSPENDED或RETIRED")
    private String status;

    @Schema(description = "启用复核人账号")
    private String approvedBy;

    @Schema(description = "启用复核人姓名快照")
    private String approvedByName;

    @Schema(description = "启用复核时间")
    private LocalDateTime approvedTime;

    @Schema(description = "暂停或退役原因")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String closeReason;

    @Schema(description = "暂停或退役操作人账号")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String closedBy;

    @Schema(description = "暂停或退役操作人姓名快照")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String closedByName;

    @Schema(description = "暂停或退役时间")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime closedTime;

    @Schema(description = "策略适用说明")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    @Schema(description = "详情版本令牌，编辑或复核时原样回传；不持久化")
    @TableField(exist = false)
    private String revisionToken;

    @Schema(description = "所属工厂编码，由当前工厂上下文自动填充", accessMode = Schema.AccessMode.READ_ONLY)
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
