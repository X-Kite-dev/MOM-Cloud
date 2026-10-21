package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 选定产出全部原耗料份额的独立纠正主账；不撤生产或质量。
 */
@Data
@TenantTable
@TableName("mes_production_feed_reversal")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "选定产出全部原耗料份额的独立纠正主账；不撤生产或质量")
public class MesProductionFeedReversalEntity extends BaseEntity<MesProductionFeedReversalEntity> {
    /**
     * 独立纠正稳定原号，租户内永久唯一。
     */
    @Schema(description = "独立纠正稳定原号，租户内永久唯一")
    private String requestId;

    /**
     * 实际原总投料主账主键。
     */
    @Schema(description = "实际原总投料主账主键")
    private Long batchId;

    /**
     * 本次实际认证纠正用户主键。
     */
    @Schema(description = "本次实际认证纠正用户主键")
    private Long actorId;

    /**
     * 实际原总账不可变证据SHA。
     */
    @Schema(description = "实际原总账不可变证据SHA")
    private String originalFingerprint;

    /**
     * 本次耗料账纠正原因，不代替生产逆向许可。
     */
    @Schema(description = "本次耗料账纠正原因，不代替生产逆向许可")
    private String reason;

    /**
     * MATERIAL_RESTORED仅表示选定原份额已恢复。
     */
    @Schema(description = "MATERIAL_RESTORED仅表示选定原份额已恢复")
    private String status;

    /**
     * 本次独立内部纠正参数协议版本。
     */
    @Schema(description = "本次独立内部纠正参数协议版本")
    private String commandVersion;

    /**
     * 固定完整内部命令原文，不重写外层未来生产命令。
     */
    @Schema(description = "固定完整内部命令原文，不重写外层未来生产命令")
    private String commandSnapshot;

    /**
     * 命令版本域及原字符串SHA-256。
     */
    @Schema(description = "命令版本域及原字符串SHA-256")
    private String commandFingerprint;

    /**
     * 本次逐产出份额恢复证据版本。
     */
    @Schema(description = "本次逐产出份额恢复证据版本")
    private String evidenceVersion;

    /**
     * 全部选定产出、原份额及逐feed前后量原文。
     */
    @Schema(description = "全部选定产出、原份额及逐feed前后量原文")
    private String evidenceSnapshot;

    /**
     * 证据版本域及完整原字符串SHA-256。
     */
    @Schema(description = "证据版本域及完整原字符串SHA-256")
    private String evidenceFingerprint;

    /**
     * 实际工厂由已认证上下文和真实原账共同确定。
     */
    @Schema(description = "实际工厂，不由客户端声明授予权限")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
