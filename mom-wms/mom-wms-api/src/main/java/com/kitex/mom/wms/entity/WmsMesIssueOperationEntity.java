package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("wms_mes_issue_operation")
public class WmsMesIssueOperationEntity extends BaseEntity<WmsMesIssueOperationEntity> {
	private Long outboxId;
	private String requestId;
	private String operationType;
	private String beforeStatus;
	private String afterStatus;
	private String detail;
	private String operatorCode;
	private LocalDateTime operationTime;
	private String factoryCode;
	private Long originalTargetId;
	private String cancellationReason;
}
