package com.kitex.mom.wms.dto.app;

/**
 * 移动建单准入只读合同，不作为后续写入授权凭证。
 *
 * @param mobileEnabled 当前仓的手机端开关和盘点作业范围是否均允许建单
 * @param mobileMessage 不允许建单时的原因，通过时为空
 */
public record WmsMobileInventoryContext(int contractVersion, int mobileContractVersion, Long tenantId,
                                        String factoryCode, String operatorCode, String warehouseCode,
                                        boolean mobileEnabled, String mobileMessage) {
}
