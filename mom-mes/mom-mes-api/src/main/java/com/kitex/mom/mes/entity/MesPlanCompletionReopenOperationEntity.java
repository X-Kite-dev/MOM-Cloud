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
 * MES完工重新打开事件的投递与人工续投永久履历。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_plan_completion_reopen_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MES完工重新打开事件投递履历")
public class MesPlanCompletionReopenOperationEntity
        extends BaseEntity<MesPlanCompletionReopenOperationEntity> {

    private Long outboxId;
    private String action;
    private Integer attemptNo;
    private String beforeStatus;
    private String afterStatus;
    private String requestId;
    private String detail;
    private LocalDateTime operationTime;
    private String factoryCode;
}
