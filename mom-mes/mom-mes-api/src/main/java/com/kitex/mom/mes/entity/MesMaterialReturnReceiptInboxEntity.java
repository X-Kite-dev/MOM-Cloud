package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * MES接收WMS生产退料实收事件的永久收件账。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("mes_material_return_receipt_inbox")
@Schema(description = "MES接收WMS生产退料实际收货事件的永久收件账")
public class MesMaterialReturnReceiptInboxEntity extends BaseEntity<MesMaterialReturnReceiptInboxEntity> {
    @Schema(description = "跨服务永久事件号")
    private String eventId;
    @Schema(description = "MES生产退料申请主键")
    private Long returnRequestId;
    @Schema(description = "WMS生产退料入库通知主键")
    private Long wmsNoticeId;
    @Schema(description = "本次实际记账入库单主键；状态事件为空")
    private Long wmsInBillId;
    @Schema(description = "冻结事件SHA-256指纹")
    private String fingerprint;
    @Schema(description = "WMS冻结事件JSON")
    private String payloadJson;
    @Schema(description = "MES永久回执JSON")
    private String receiptJson;
    @Schema(description = "MES接受时间")
    private LocalDateTime acceptedTime;
    @Schema(description = "实际工厂编码")
    private String factoryCode;
}
