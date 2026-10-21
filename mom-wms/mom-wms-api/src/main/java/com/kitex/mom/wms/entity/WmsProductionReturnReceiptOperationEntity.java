package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 生产退料收货与差异处置不可变履历。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("wms_production_return_receipt_operation")
@Schema(description = "生产退料收货终结与差异处置不可变履历")
public class WmsProductionReturnReceiptOperationEntity extends BaseEntity<WmsProductionReturnReceiptOperationEntity> {
    @Schema(description = "生产退料入库通知主键")
    private Long noticeId;
    @Schema(description = "人工操作永久幂等请求号")
    private String requestId;
    @Schema(description = "FINALIZE或DISPOSITION")
    private String operationType;
    @Schema(description = "操作前实收状态")
    private String beforeStatus;
    @Schema(description = "操作后实收状态")
    private String afterStatus;
    @Schema(description = "终结数量快照或差异处置依据")
    private String detail;
    @Schema(description = "操作人工号")
    private String operatorCode;
    @Schema(description = "操作人姓名")
    private String operatorName;
    @Schema(description = "操作时间")
    private LocalDateTime operationTime;
    @Schema(description = "实际工厂编码")
    private String factoryCode;
}
