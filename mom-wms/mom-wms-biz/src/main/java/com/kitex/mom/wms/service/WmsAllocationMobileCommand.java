package com.kitex.mom.wms.service;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.app.WmsAllocationBillAppDTO;

import java.util.LinkedHashMap;
import java.util.Map;

/** 执行与撤销共用原请求规范，快照保持已发布移动接口的字段及顺序。 */
public final class WmsAllocationMobileCommand {
	private WmsAllocationMobileCommand() {
	}

	public static void normalize(WmsAllocationBillAppDTO command, String factory) {
		require(command != null && command.getOperateNum() != null && Double.isFinite(command.getOperateNum())
				&& command.getOperateNum() > 0, "操作数量必须为大于0的有效数值");
		command.setRequestId(code(command.getRequestId(), 64, "原调拨请求号无效"));
		command.setBillNo(code(command.getBillNo(), 100, "调拨单号无效"));
		command.setOldWarehouseCode(code(command.getOldWarehouseCode(), 100, "调出仓库无效"));
		command.setNewWarehouseCode(code(command.getNewWarehouseCode(), 100, "调入仓库无效"));
		command.setOldSiteCode(code(command.getOldSiteCode(), 100, "调出库位无效"));
		command.setNewSiteCode(code(command.getNewSiteCode(), 100, "调入库位无效"));
		require(command.getMatCode() != null && !command.getMatCode().isBlank(), "请选择调拨物料");
		require(!command.getOldWarehouseCode().contains("*") && !command.getOldWarehouseCode().contains("?"),
				"仓库编码不能包含权限通配符");
		require(!command.getOldWarehouseCode().equals(command.getNewWarehouseCode()), "源仓和目标仓不能相同");
		require(command.getSourceStockId() == null || command.getSourceStockId() > 0, "原调拨实物库存标识无效");
		command.setFactoryCode(factory);
	}

	public static String snapshot(WmsAllocationBillAppDTO command) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("billNo", command.getBillNo());
		snapshot.put("factory", command.getFactoryCode());
		snapshot.put("sourceWarehouse", command.getOldWarehouseCode());
		snapshot.put("sourceSite", command.getOldSiteCode());
		snapshot.put("sourceContainer", command.getOldContainerCode());
		snapshot.put("targetWarehouse", command.getNewWarehouseCode());
		snapshot.put("targetSite", command.getNewSiteCode());
		snapshot.put("targetContainer", command.getNewContainerCode());
		snapshot.put("material", command.getMatCode());
		snapshot.put("batch", command.getMatBatch());
		snapshot.put("supplier", command.getSupplierCode());
		snapshot.put("quantity", command.getOperateNum());
		snapshot.put("unit", command.getMatUnit());
		snapshot.put("sourceStockId", command.getSourceStockId());
		snapshot.put("matBarcode", command.getMatBarcode());
		return JSONUtil.toJsonStr(snapshot);
	}

	private static String code(String value, int limit, String message) {
		require(value != null && !value.isBlank() && value.trim().length() <= limit, message);
		return value.trim();
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
