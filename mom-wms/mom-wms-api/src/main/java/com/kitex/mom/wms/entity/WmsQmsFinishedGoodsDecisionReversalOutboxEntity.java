package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * WMS成品实物红冲结果向QMS可靠回送的事务Outbox。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_qms_finished_goods_decision_reversal_outbox")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "WMS成品红冲结果到QMS的可靠投递事件")
public class WmsQmsFinishedGoodsDecisionReversalOutboxEntity
        extends BaseEntity<WmsQmsFinishedGoodsDecisionReversalOutboxEntity> {
    @Schema(description = "跨服务永久事件号")
    private String eventId;
    @Schema(description = "WMS红冲申请ID")
    private Long correctionId;
    @Schema(description = "QMS质量逆向授权ID")
    private Long reversalId;
    @Schema(description = "冻结事件SHA-256指纹")
    private String fingerprint;
    @Schema(description = "冻结事件载荷JSON")
    private String payloadJson;
    @Schema(description = "PENDING/RETRY/SENDING/DELIVERED/DEAD")
    private String status;
    @Schema(description = "历史总投递次数")
    private Integer attemptCount;
    @Schema(description = "本轮投递次数；人工续投后归零")
    private Integer cycleAttempts;
    @Schema(description = "下次允许投递时间")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextAttemptTime;
    @Schema(description = "当前投递租约令牌")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String leaseToken;
    @Schema(description = "当前投递租约到期时间")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseUntil;
    @Schema(description = "最后一次失败原因")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;
    @Schema(description = "QMS永久回执JSON")
    private String receiptJson;
    @Schema(description = "QMS确认时间")
    private LocalDateTime deliveredTime;
    @Schema(description = "实际工厂编码")
    private String factoryCode;
}
