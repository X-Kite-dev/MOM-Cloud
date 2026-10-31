package com.kitex.mom.wms.entity.organization;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织调拨仓库策略；持久化由显式租户与本侧仓库查询负责。
 */
@Data
public class WmsOrganizationTransferPolicy {
	private Long id;
	private Long tenantId;
	private String factoryCode;
	private String warehouseCode;
	private String warehouseName;
	private String companyCode;
	private String companyName;
	private String updatedBy;
	private Boolean enabled;
	private Boolean crossCompany;
	private Integer version;
	private LocalDateTime updatedTime;
}
