package com.kitex.mom.wms.dto;

import java.util.List;

/**
 * 预留配置内容版本，不承诺单调审计序号或永久操作回执。
 */
public final class WmsReservationConfigurationDTO {
	private WmsReservationConfigurationDTO() {
	}

	public record Policy(String id, String movementType, Boolean reservationAllowed, Boolean status, String remark) {
	}

	public record Snapshot(int contractVersion, String tenantId, String factoryCode, String revision,
	                       List<Policy> policies) {
	}

	public record Save(String expectedRevision, Policy policy) {
	}

	public record Saved(Snapshot configuration, String policyId) {
	}
}
