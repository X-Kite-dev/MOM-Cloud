package com.kitex.mom.mes.entity;

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

/**
 * 退回上序申请、复核、驳回和撤回的永久幂等操作回执。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_identifier_route_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "退回上序操作回执")
public class MesIdentifierRouteOperationEntity extends BaseEntity<MesIdentifierRouteOperationEntity> {
    private Long routeReturnId;
    private String requestId;
    private String requestFingerprint;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private Long operatorId;
    private String operatorCode;
    private String operatorName;
    private String reason;
    private String evidenceSnapshot;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime operatedTime;

    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
