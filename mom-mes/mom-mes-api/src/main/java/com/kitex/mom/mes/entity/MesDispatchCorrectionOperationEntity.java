package com.kitex.mom.mes.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 精确来源与永久审计账；仅由专用Mapper写入。
 */
@Data
public class MesDispatchCorrectionOperationEntity {

    private Long id;

    private Long tenantId;

    private String factoryCode;

    private Long correctionId;

    private String requestId;

    private String requestHash;

    private String action;

    private Long actorId;

    private String actorCode;

    private String reason;

    private LocalDateTime createdTime;

    private String resultJson;
}
