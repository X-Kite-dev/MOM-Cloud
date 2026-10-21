package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
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
 * 成品实收入库红冲的不可变操作履历。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_finished_goods_receipt_correction_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "成品实收入库红冲不可变操作履历")
public class WmsFinishedGoodsReceiptCorrectionOperationEntity extends BaseEntity<WmsFinishedGoodsReceiptCorrectionOperationEntity> {
    @Schema(description = "红冲申请ID")
    private Long correctionId;
    @Schema(description = "本次操作永久幂等号")
    private String commandId;
    @Schema(description = "操作命令SHA-256指纹")
    private String commandFingerprint;
    @Schema(description = "SUBMIT/WITHDRAW/REJECT/STALE/APPROVE_EXECUTE")
    private String operationType;
    @Schema(description = "操作前状态")
    private String beforeStatus;
    @Schema(description = "操作后状态")
    private String afterStatus;
    @Schema(description = "操作原因或冻结结果JSON")
    private String content;
    @Schema(description = "实名操作人账号")
    private String operatorCode;
    @Schema(description = "实名操作人名称")
    private String operatorName;
    @Schema(description = "操作时间")
    private LocalDateTime operationTime;
    @Schema(description = "实际工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
