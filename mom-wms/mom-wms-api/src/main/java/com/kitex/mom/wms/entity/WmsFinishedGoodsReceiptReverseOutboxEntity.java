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
 * WMS成品实物红冲向MES可靠投递的事务Outbox。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_finished_goods_receipt_reverse_outbox")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "WMS成品实物红冲到MES的可靠投递事件")
public class WmsFinishedGoodsReceiptReverseOutboxEntity extends BaseEntity<WmsFinishedGoodsReceiptReverseOutboxEntity> {
    @Schema(description = "跨服务永久事件号")
    private String eventId;
    @Schema(description = "WMS红冲申请ID")
    private Long correctionId;
    @Schema(description = "WMS成品入库通知ID")
    private Long noticeId;
    @Schema(description = "MES原成品入库永久请求号")
    private String sourceMesInboundRequestId;
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
    @Schema(description = "MES永久回执JSON")
    private String receiptJson;
    @Schema(description = "MES确认时间")
    private LocalDateTime deliveredTime;
    @Schema(description = "实际工厂编码")
    private String factoryCode;
}
