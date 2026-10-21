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
 * WMS向MES可靠投递生产退料实收事实的Outbox。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("wms_mes_material_return_receipt_outbox")
@Schema(description = "WMS生产退料实际收货向MES可靠投递箱")
public class WmsMesMaterialReturnReceiptOutboxEntity extends BaseEntity<WmsMesMaterialReturnReceiptOutboxEntity> {
    @Schema(description = "跨服务永久事件号")
    private String eventId;
    @Schema(description = "WMS入库通知主键")
    private Long noticeId;
    @Schema(description = "本次实际记账入库单主键；状态事件为空")
    private Long inBillId;
    @Schema(description = "MES生产退料申请主键")
    private Long returnRequestId;
    @Schema(description = "冻结事件SHA-256指纹")
    private String fingerprint;
    @Schema(description = "冻结事件JSON")
    private String payloadJson;
    @Schema(description = "PENDING/SENDING/RETRY/DELIVERED/DEAD")
    private String status;
    @Schema(description = "历史累计投递次数")
    private Integer attemptCount;
    @Schema(description = "当前人工/自动续跑周期次数")
    private Integer cycleAttempts;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextAttemptTime;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String leaseToken;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseUntil;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;
    @Schema(description = "MES永久回执JSON")
    private String receiptJson;
    @Schema(description = "MES确认时间")
    private LocalDateTime deliveredTime;
    @Schema(description = "实际工厂编码")
    private String factoryCode;
}
