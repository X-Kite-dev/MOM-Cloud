package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通用业务菜单类型
 *
 * @author kitex
 * @date 2025-1-14 10:06:59
 **/
@Getter
@AllArgsConstructor
public enum MenuCommonBizTypeEnum {

//	/**
//	 * 采购收料
//	 */

	/**
	 * 入库单
	 */
	IN_BILL("in_bill", "入库单", "/wms/commonBiz/inBill/index", "", 11),

	/**
	 * 出库单
	 */
	OUT_BILL("out_bill", "出库单", "/wms/commonBiz/outBill/index", "", 12),


	/**
	 * 批量拣货
	 */
	OUT_NOTICE_BATCH("out_notice_batch", "批量拣货", "/wms/commonBiz/wmsOutNoticeBatch/index", "", 13),

	/**
	 * 实时库存
	 */
	LIVE_STOCK("live_stock", "实时库存", "/wms/commonBiz/liveStock", "/pages-wms/warehouseIn/live/index", 14),
	/**
	 * 出入库流水
	 */
	INOUT_FLOW("inout_flow", "出入库流水", "/wms/commonBiz/inoutFlow/index", "", 16),
	/**
	 * 库存盘点
	 */
	INVENTORY("inventory", "库存盘点", "/wms/commonBiz/insideInventoryPlan/index",
			"/pages-wms/warehouseIn/inventoryCheck/index", 18),
	/**
	 * 调拨单
	 */
	ALLOCATION("allocation", "调拨单", "/wms/commonBiz/allocationBill/bill/index",
			"/pages-wms/order/allocationBill/receive", 20),
	/**
	 * 调拨接收
	 */
	ALLOCATION_RECEIVE("allocation_receive", "调拨接收", "/wms/commonBiz/allocationBill/receive/index",
			"/pages-wms/order/allocationBill/inTransit", 22),
	ORGANIZATION_TRANSFER("organization_transfer", "组织调拨", "/wms/commonBiz/organizationTransfer/index",
			"/pages-wms/order/organizationTransfer/index", 23),
	SPECIAL_MOVEMENT("special_movement", "特殊收发", "/wms/commonBiz/specialMovement/index",
			"/pages-wms/order/specialMovement/index", 25),
	/**
	 * 库内移动
	 */
	MOVE_SITE("move_site", "库内移动", "/wms/commonBiz/materialMoveSite/index",
			"/pages-wms/warehouseIn/transfer/index",
			24);

	private final String code;

	private final String label;

	private final String value;

	private final String appPath;

	private final Integer sort;

	/**
	 * 根据code获取枚举
	 *
	 * @param code 编码
	 * @return 对应的枚举，如果不存在则返回null
	 */
	public static MenuCommonBizTypeEnum getByCode(String code) {
		for (MenuCommonBizTypeEnum type : values()) {
			if (type.getCode().equals(code)) {
				return type;
			}
		}
		return null;
	}

	/**
	 * 根据value获取枚举
	 *
	 * @param value 枚举值
	 * @return 对应的枚举，如果不存在则返回null
	 */
	public static MenuCommonBizTypeEnum getByValue(String value) {
		for (MenuCommonBizTypeEnum type : values()) {
			if (type.getValue().equals(value)) {
				return type;
			}
		}
		return null;
	}

	public boolean equals(MenuCommonBizTypeEnum type) {
		return this.getCode().equals(type.getCode());
	}
}
