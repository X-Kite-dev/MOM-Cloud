package com.kitex.mom.wms.entity.organization;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 组织调拨数量守恒行；持久化由显式租户与本侧仓库查询负责。
 */
@Data
public class WmsOrganizationTransferLine {
	private Long id;
	private Long tenantId;
	private Long billId;
	private Long sourceStockId;
	private Long issueFlowId;
	private BigDecimal quantity;
	private BigDecimal receivedQuantity;
	private BigDecimal returnedQuantity;
	private BigDecimal exceptionQuantity;
	private String sourceSnapshot;
	private String issueSnapshot;
}
