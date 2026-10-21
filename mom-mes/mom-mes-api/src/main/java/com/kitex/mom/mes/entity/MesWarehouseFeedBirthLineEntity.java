package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.mes.serialization.MesNativeQuantitySerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 投料出生逐库存来源；每行唯一占用一条真实OUT及其OLD/NEW。
 */
@Data
@TenantTable
@TableName("mes_warehouse_feed_birth_line")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "投料出生逐库存来源；每行唯一占用一条真实OUT及其OLD/NEW。")
public class MesWarehouseFeedBirthLineEntity extends BaseEntity<MesWarehouseFeedBirthLineEntity> {

    /**
     * 实际授权工厂。
     */
    @Schema(description = "实际授权工厂")
    private String factoryCode;

    /**
     * 永久投料出生主账主键。
     */
    @Schema(description = "永久投料出生主账主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long birthId;

    /**
     * 原回执稳定行序号。
     */
    @Schema(description = "原回执稳定行序号")
    private Integer sequenceNo;

    /**
     * 真实被扣减库存主键。
     */
    @Schema(description = "真实被扣减库存主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long stockId;

    /**
     * 真实领出OUT主键。
     */
    @Schema(description = "真实领出OUT主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long outflowId;

    /**
     * 数据库实际OLD/NEW变动唯一键。
     */
    @Schema(description = "数据库实际OLD/NEW变动唯一键")
    private String mutationKey;

    /**
     * 该来源原单位扣减量。
     */
    @Schema(description = "该来源原单位扣减量")
    @JsonSerialize(using = MesNativeQuantitySerializer.class)
    private BigDecimal quantity;

    /**
     * 原库存原单位。
     */
    @Schema(description = "原库存原单位")
    private String unit;

    /**
     * 真实UPDATE的完整OLD，不以当前库存补来源。
     */
    @Schema(description = "真实UPDATE的完整OLD，不以当前库存补来源")
    private String sourceSnapshot;

    /**
     * 真实UPDATE的完整NEW，包含归零归档事实。
     */
    @Schema(description = "真实UPDATE的完整NEW，包含归零归档事实")
    private String afterSnapshot;

    /**
     * 本次真实OUT完整原事实。
     */
    @Schema(description = "本次真实OUT完整原事实")
    private String flowSnapshot;

    /**
     * 逐源证据协议版本。
     */
    @Schema(description = "逐源证据协议版本")
    private String evidenceVersion;

    /**
     * 逐源固定文本字段完整原字符串。
     */
    @Schema(description = "逐源固定文本字段完整原字符串")
    private String evidenceSnapshot;

    /**
     * 逐源版本域SHA256。
     */
    @Schema(description = "逐源版本域SHA256")
    private String evidenceFingerprint;
}
