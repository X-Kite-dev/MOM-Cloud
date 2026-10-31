package com.kitex.mom.wms.entity.organization;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织调拨双方协同单；持久化由显式租户与本侧仓库查询负责。
 */
@Data
public class WmsOrganizationTransferBill {
	private Long id;
	private Long createdById;
	private Long confirmedById;
	private Long tenantId;
	private Long sourcePolicyId;
	private Long targetPolicyId;
	private String billNo;
	private String sourceFactoryCode;
	private String sourceWarehouseCode;
	private String sourceWarehouseName;
	private String sourceCompanyCode;
	private String sourceCompanyName;
	private String targetFactoryCode;
	private String targetWarehouseCode;
	private String targetWarehouseName;
	private String targetCompanyCode;
	private String targetCompanyName;
	private String state;
	private String previousState;
	private String createdBy;
	private String confirmedBy;
	private String agreementRef;
	private String remark;
	private String exceptionReason;
	private String exceptionEvidence;
	private Integer version;
	private LocalDateTime createdTime;
	private LocalDateTime updatedTime;
}
