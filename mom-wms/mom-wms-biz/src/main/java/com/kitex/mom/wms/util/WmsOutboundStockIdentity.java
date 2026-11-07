package com.kitex.mom.wms.util;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;

import java.util.Objects;

/**
 * 精确原库存出库的纯身份校验，供草稿预检与锁内扣库共用。
 * 不读取或锁定库存，不补写缺失字段，不判断或占用数量，也不替代货权及质量门禁。
 */
public final class WmsOutboundStockIdentity {

	private WmsOutboundStockIdentity() {
	}

	/**
	 * 核对原库存主键、实际组织和完整物流身份；可空物流属性仅null与空串等价，不裁剪非空值。
	 *
	 * @param detail  出库明细，须已沿实际主单设置工厂及仓库
	 * @param stock   当前读取的精确原库存；正式扣库时须由调用方先锁定
	 * @param tenant  当前实际租户，不能从上传明细推断
	 * @param factory 当前实际工厂，不能从上传明细推断
	 * @throws CheckedException 原库存无效、跨组织或物流身份与出库明细不一致时拒绝
	 */
	public static void requireMatches(WmsOutBillDetailEntity detail, WmsLiveStockEntity stock,
	                                  Long tenant, String factory) {
		if (detail == null || detail.getSourceStockId() == null || detail.getSourceStockId() <= 0) {
			throw new CheckedException("请选择有效的来源库存");
		}
		if (stock == null || !Objects.equals(detail.getSourceStockId(), stock.getId())
				|| !"0".equals(stock.getDelFlag())) {
			throw new CheckedException("所选来源库存已不存在，请重新选择库存");
		}
		if (tenant == null || tenant <= 0 || StrUtil.isBlank(factory)
				|| !Objects.equals(tenant, stock.getTenantId())
				|| (detail.getTenantId() != null && !Objects.equals(tenant, detail.getTenantId()))
				|| !Objects.equals(factory, stock.getFactoryCode())
				|| !Objects.equals(factory, detail.getFactoryCode())) {
			throw new CheckedException("所选来源库存不属于当前租户和工厂");
		}
		if (StrUtil.isBlank(detail.getMatCode()) || StrUtil.isBlank(detail.getMatUnit())
				|| StrUtil.isBlank(detail.getWarehouseCode()) || StrUtil.isBlank(detail.getSiteCode())
				|| !Objects.equals(stock.getMatCode(), detail.getMatCode())
				|| !Objects.equals(stock.getMatUnit(), detail.getMatUnit())
				|| !Objects.equals(stock.getWarehouseCode(), detail.getWarehouseCode())
				|| !Objects.equals(stock.getSiteCode(), detail.getSiteCode())
				|| !Objects.equals(StrUtil.nullToEmpty(stock.getContainerCode()),
				StrUtil.nullToEmpty(detail.getContainerCode()))
				|| !Objects.equals(StrUtil.nullToEmpty(stock.getMatBatch()),
				StrUtil.nullToEmpty(detail.getMatBatch()))
				|| !Objects.equals(StrUtil.nullToEmpty(stock.getSupplierCode()),
				StrUtil.nullToEmpty(detail.getSupplierCode()))
				|| !Objects.equals(StrUtil.nullToEmpty(stock.getMatBarcode()),
				StrUtil.nullToEmpty(detail.getMatBarcode()))) {
			throw new CheckedException(
					"所选库存的物料、单位、供应商、批次、条码、仓库、库位或容器与出库明细不一致，请重新选择库存");
		}
	}

}
