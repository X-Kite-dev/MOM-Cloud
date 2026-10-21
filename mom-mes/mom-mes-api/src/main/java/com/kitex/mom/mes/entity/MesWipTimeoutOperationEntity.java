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

@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_wip_timeout_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "在制超时事件不可变操作履历")
public class MesWipTimeoutOperationEntity extends BaseEntity<MesWipTimeoutOperationEntity> {
    private String requestId;
    private Long handleId;
    private String incidentKey;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String content;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;

    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
