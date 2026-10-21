package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 独立总耗料的一份真实GOOD或NG产出；两个报告主键域严格分开。
 */
@Data
@TenantTable
@TableName("mes_production_feed_output")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "独立总耗料的一份真实GOOD或NG产出；两个报告主键域严格分开")
public class MesProductionFeedOutputEntity extends BaseEntity<MesProductionFeedOutputEntity> {
    /**
     * 所属独立生产总投料账主键。
     */
    @Schema(description = "所属独立生产总投料账主键")
    private Long batchId;

    /**
     * 本次产出稳定键，不合并不同原生产事实。
     */
    @Schema(description = "本次产出稳定键，不合并不同原生产事实")
    private String outputKey;

    /**
     * GOOD或NG，不能从数量或空报告主键猜测。
     */
    @Schema(description = "GOOD或NG，不能从数量或空报告主键猜测")
    private String outputKind;

    /**
     * 真实合格报告主键，仅GOOD填写。
     */
    @Schema(description = "真实合格报告主键，仅GOOD填写")
    private Long goodReportId;

    /**
     * 真实不良报告主键，仅NG填写，不得填入GOOD域。
     */
    @Schema(description = "真实不良报告主键，仅NG填写，不得填入GOOD域")
    private Long ngReportId;

    /**
     * 数据库首次实际报告INSERT证据主键，禁止后补旧来源。
     */
    @Schema(description = "数据库首次实际报告INSERT证据主键，禁止后补旧来源")
    private Long sourceBirthId;

    /**
     * 该份真实产出的正量原数量，不是本次总加工量。
     */
    @Schema(description = "该份真实产出的正量原数量，不是本次总加工量")
    private BigDecimal quantity;

    /**
     * 该份原生产基本单位。
     */
    @Schema(description = "该份原生产基本单位")
    private String productionUnit;

    /**
     * 首次数据库实际报告完整标量事实原字符串，不由当前主数据重建。
     */
    @Schema(description = "首次数据库实际报告完整标量事实原字符串，不由当前主数据重建")
    private String sourceSnapshot;

    /**
     * 真实工厂，服务端按已认证上下文确定。
     */
    @Schema(description = "真实工厂，不能由页面声明授权")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
