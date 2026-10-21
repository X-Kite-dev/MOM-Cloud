package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 交期答复申请、双岗确认、订单变更结果和对客答复的不可变操作账。 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("oms_delivery_promise_operation")
@Schema(description = "交期答复不可变操作履历")
public class OmsDeliveryPromiseOperationEntity extends BaseEntity<OmsDeliveryPromiseOperationEntity> {
    @Schema(description = "操作永久幂等请求号")
    private String requestId;
    @Schema(description = "操作命令SHA-256指纹")
    private String requestFingerprint;
    @Schema(description = "交期答复主单ID")
    private Long promiseId;
    @Schema(description = "操作类型")
    private String operationType;
    @Schema(description = "操作前状态")
    private String beforeStatus;
    @Schema(description = "操作后状态")
    private String afterStatus;
    @Schema(description = "操作结论或说明")
    private String content;
    @Schema(description = "操作人工号")
    private String operatorCode;
    @Schema(description = "操作人姓名")
    private String operatorName;
    @Schema(description = "操作时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime operationTime;
    @Schema(description = "所属工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
