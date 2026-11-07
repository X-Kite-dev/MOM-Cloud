package com.kitex.mom.wms.dto.app;

import com.kitex.mom.wms.dto.WmsStockTraceDTO;

/**
 * 移动库存详情的只读追溯合同；原查询、仓库授权范围和证据质量由正式追溯服务维护。
 */
public final class WmsMobileStockTraceDTO {
	private WmsMobileStockTraceDTO() {
	}

	/**
	 * @param contractVersion 当前移动追溯合同版本，为1
	 * @param tenantId        实际租户标识的完整文本
	 * @param factoryCode     已获实名read授权的实际工厂
	 * @param operatorCode    当前实际登录账号
	 * @param canView         是否具有既有库存追溯查看权限，不代表获得任意仓库查看权
	 */
	public record Context(int contractVersion, String tenantId, String factoryCode,
	                      String operatorCode, boolean canView) {
	}

	/**
	 * @param identity 本次读取已核实的实际组织、账号和追溯权限
	 * @param stockId  原查询库存主键的完整文本，可关联已消耗库存的历史证据
	 * @param evidence 正式WMS来源、流转及当前占用证据；保留截断/缺口，不认定MES投料已验证
	 */
	public record View(Context identity, String stockId, WmsStockTraceDTO.Evidence evidence) {
	}
}
