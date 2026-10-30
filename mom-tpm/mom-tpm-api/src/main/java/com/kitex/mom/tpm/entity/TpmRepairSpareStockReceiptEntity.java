package com.kitex.mom.tpm.entity;

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
 * 维修任务备件库存出入账的不可变回执。
 *
 * <p>回执由原备件申请审批事务自动生成，不是新的备件业务单。OUT 表示维修领料出库，
 * IN 表示维修剩余备件退回；明细及库存流水身份冻结在 payloadJson 中。</p>
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("tpm_repair_spare_stock_receipt")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "维修备件库存出入账不可变回执")
public class TpmRepairSpareStockReceiptEntity extends BaseEntity<TpmRepairSpareStockReceiptEntity> {

    @Schema(description = "原备件申请主键")
    private Long applicationId;

    @Schema(description = "原备件申请号")
    private String applyCode;

    @Schema(description = "维修任务主键")
    private Long repairTaskId;

    @Schema(description = "维修任务编码")
    private String repairTaskCode;

    @Schema(description = "库存方向：OUT领料、IN退回")
    private String movementType;

    @Schema(description = "审批人账号")
    private String approvedBy;

    @Schema(description = "审批人姓名")
    private String approvedByName;

    @Schema(description = "库存变动审批时间")
    private LocalDateTime approvedTime;

    @Schema(description = "含原库存流水主键的冻结回执载荷")
    private String payloadJson;

    @Schema(description = "冻结回执载荷SHA-256")
    private String payloadFingerprint;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
