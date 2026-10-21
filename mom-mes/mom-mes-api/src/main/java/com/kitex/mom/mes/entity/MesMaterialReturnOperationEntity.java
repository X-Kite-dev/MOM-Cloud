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

import java.time.LocalDateTime;

/**
 * 线边余料受控退库的永久幂等操作履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_material_return_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "线边余料受控退库不可变操作履历")
public class MesMaterialReturnOperationEntity extends BaseEntity<MesMaterialReturnOperationEntity> {
    @Schema(description = "退料申请主键")
    private Long returnRequestId;
    @Schema(description = "操作永久幂等请求号")
    private String requestId;
    @Schema(description = "操作命令指纹")
    private String requestFingerprint;
    @Schema(description = "SUBMIT/APPROVE/REJECT/WITHDRAW/MARK_STALE/WMS_RECEIPT")
    private String operationType;
    @Schema(description = "操作前状态")
    private String beforeStatus;
    @Schema(description = "操作后状态")
    private String afterStatus;
    @Schema(description = "操作依据或结果")
    private String content;
    @Schema(description = "操作人主键")
    private Long operatorId;
    @Schema(description = "操作人工号")
    private String operatorCode;
    @Schema(description = "操作人姓名")
    private String operatorName;
    @Schema(description = "操作时间")
    private LocalDateTime operationTime;
    @Schema(description = "实际工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
