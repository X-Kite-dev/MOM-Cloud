package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 人工发料操作的组织上下文或原登记凭据；不代表MES已送达或现场已签收。
 */
@Data
@Accessors(chain = true)
public class WmsMesIssueOperationProof {
	private Integer contractVersion = 1;
	private Long tenantId;
	private String factoryCode;
	private String operatorCode;
	private Long operationId;
	private String requestId;
	private String operationType;
	private Long outboxId;
	private Long wmsBillId;
	private String wmsBillNo;
	private String eventId;
	private String reason;
	private String registeredStatus;
	private LocalDateTime operationTime;
	private Boolean cancellationSupported;
	private String commandStatus;
	private String cancellationReason;
}
