package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MES接收WMS成品实物红冲事件的永久收件箱与回执。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_finished_goods_receipt_reverse_inbox")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MES成品实物红冲永久收件箱及回执")
public class MesFinishedGoodsReceiptReverseInboxEntity extends BaseEntity<MesFinishedGoodsReceiptReverseInboxEntity> {
    @Schema(description = "WMS跨服务永久事件号")
    private String eventId;
    @Schema(description = "冻结事件SHA-256指纹")
    private String fingerprint;
    @Schema(description = "WMS红冲申请ID")
    private Long correctionId;
    @Schema(description = "WMS成品入库通知ID")
    private Long wmsNoticeId;
    @Schema(description = "MES原成品入库永久请求号")
    private String sourceMesInboundRequestId;
    @Schema(description = "MES原成品入库回执ID")
    private Long mesReceiptId;
    @Schema(description = "MES生产工单ID")
    private Long mesTaskId;
    @Schema(description = "本次全量回冲数量")
    private BigDecimal reversedQuantity;
    @Schema(description = "WMS冻结事件载荷JSON")
    private String payloadJson;
    @Schema(description = "MES永久确认回执JSON")
    private String receiptJson;
    @Schema(description = "MES接收完成时间")
    private LocalDateTime acceptedTime;
    @Schema(description = "实际工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
