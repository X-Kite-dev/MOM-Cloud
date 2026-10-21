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
 * 投料出生永久主账；领出凭证不是产量或质量放行。
 */
@Data
@TenantTable
@TableName("mes_warehouse_feed_birth")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "投料出生永久主账；领出凭证不是产量或质量放行。")
public class MesWarehouseFeedBirthEntity extends BaseEntity<MesWarehouseFeedBirthEntity> {

    /**
     * 实际授权工厂。
     */
    @Schema(description = "实际授权工厂")
    private String factoryCode;

    /**
     * 首次实际投料记录主键。
     */
    @Schema(description = "首次实际投料记录主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long feedId;

    /**
     * 首次实际认证操作人主键。
     */
    @Schema(description = "首次实际认证操作人主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long actorId;

    /**
     * 真实父工单主键。
     */
    @Schema(description = "真实父工单主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    /**
     * 真实派工主键。
     */
    @Schema(description = "真实派工主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long arrangeId;

    /**
     * 本次原单位领出总量。
     */
    @Schema(description = "本次原单位领出总量")
    @JsonSerialize(using = MesNativeQuantitySerializer.class)
    private BigDecimal quantity;

    /**
     * 原计量单位。
     */
    @Schema(description = "原计量单位")
    private String unit;

    /**
     * 原派工实际接收工位；空值表示未指定，不猜源库位。
     */
    @Schema(description = "原派工实际接收工位；空值表示未指定，不猜源库位")
    private String receivingWorksiteCode;

    /**
     * 首次投料全部原属性，不含可变已耗量锁定及更新审计。
     */
    @Schema(description = "首次投料全部原属性，不含可变已耗量锁定及更新审计")
    private String feedSnapshot;

    /**
     * 实际父工单原BOM全文。
     */
    @Schema(description = "实际父工单原BOM全文")
    private String bomSnapshot;

    /**
     * 领料时实际派工完整原事实。
     */
    @Schema(description = "领料时实际派工完整原事实")
    private String arrangeSnapshot;

    /**
     * 真实领出库存行数。
     */
    @Schema(description = "真实领出库存行数")
    private Integer sourceCount;

    /**
     * 投料出生原证据协议版本。
     */
    @Schema(description = "投料出生原证据协议版本")
    private String evidenceVersion;

    /**
     * 主账及全部逐源证据原字符串。
     */
    @Schema(description = "主账及全部逐源证据原字符串")
    private String evidenceSnapshot;

    /**
     * 版本域SHA256。
     */
    @Schema(description = "版本域SHA256")
    private String evidenceFingerprint;

    /**
     * 固定RECORDED；不代表生产执行或库存再次入账。
     */
    @Schema(description = "固定RECORDED；不代表生产执行或库存再次入账")
    private String status;
}
