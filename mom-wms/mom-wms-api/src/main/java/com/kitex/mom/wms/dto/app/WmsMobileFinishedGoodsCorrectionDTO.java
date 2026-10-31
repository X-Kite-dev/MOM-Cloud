package com.kitex.mom.wms.dto.app;

import java.util.List;

/**
 * 单仓移动纠错的新作业准入，不改变原申请或回执版本。
 */
public final class WmsMobileFinishedGoodsCorrectionDTO {

	private WmsMobileFinishedGoodsCorrectionDTO() {
	}

	public record ContextQuery(Long noticeId, String warehouseCode, List<Long> stockIds) {
	}

	public record Context(int contractVersion, int mobileContractVersion, String tenantId,
	                      String factoryCode, String operatorCode, String noticeId, String warehouseCode,
	                      List<String> stockIds, boolean mobileEnabled, String mobileMessage) {
	}
}
