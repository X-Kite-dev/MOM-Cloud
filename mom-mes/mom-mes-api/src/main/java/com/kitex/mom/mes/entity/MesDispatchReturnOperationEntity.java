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
 * 已接收未开工退派的不可变操作与幂等回执。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_dispatch_return_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MES受控退派不可变操作履历")
public class MesDispatchReturnOperationEntity extends BaseEntity<MesDispatchReturnOperationEntity> {
    @Schema(description = "退派申请主键")
    private Long returnRequestId;
    @Schema(description = "操作永久幂等请求号")
    private String requestId;
    @Schema(description = "操作命令指纹")
    private String requestFingerprint;
    @Schema(description = "SUBMIT/APPROVE/REJECT/WITHDRAW/MARK_STALE/SUPERSEDE_BY_PLAN")
    private String operationType;
    @Schema(description = "操作前申请状态")
    private String beforeStatus;
    @Schema(description = "操作后申请状态")
    private String afterStatus;
    @Schema(description = "操作依据或执行结果")
    private String content;
    @Schema(description = "操作人主键；系统接管时为空")
    private Long operatorId;
    @Schema(description = "操作人工号；系统接管时为SYSTEM")
    private String operatorCode;
    @Schema(description = "操作人姓名")
    private String operatorName;
    @Schema(description = "操作发生时间")
    private LocalDateTime operationTime;
    @Schema(description = "实际工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
