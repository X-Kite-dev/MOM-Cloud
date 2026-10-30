package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_workpiece_time_operation")
@EqualsAndHashCode(callSuper = true)
public class MesWorkpieceTimeOperationEntity extends BaseEntity<MesWorkpieceTimeOperationEntity> {

    private String factoryCode;
    private String requestId;
    private String requestHash;
    private Long sessionId;
    private Long arrangeId;
    private Long actorId;
    private String action;
    private String receiptJson;
}
