package com.kitex.mom.wms.dto.app;

import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.Action;

import java.util.List;

/**
 * 独立手机通道标记不进入原操作正文，跨版本恢复沿用原号及指纹。
 */
public final class WmsMobileOrganizationTransferDTO {

	private WmsMobileOrganizationTransferDTO() {
	}

	public record MobileContext(int contractVersion, Long tenantId, String factoryCode,
	                            String operatorCode, String warehouseCode, List<Action> permissions,
	                            boolean canConfigure, Long operatorId, int mobileContractVersion,
	                            boolean mobileEnabled, String mobileMessage) {
	}
}
