package com.kitex.mom.wms.dto.app;

/**
 * 移动建单准入只读合同，不作为后续写入授权凭证。
 */
public record WmsMobileInventoryContext(int contractVersion, int mobileContractVersion, Long tenantId,
                                        String factoryCode, String operatorCode, String warehouseCode,
                                        boolean mobileEnabled, String mobileMessage) {
}
