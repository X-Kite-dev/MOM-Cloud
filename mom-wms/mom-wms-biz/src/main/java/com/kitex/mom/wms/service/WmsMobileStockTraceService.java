package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsStockTraceDTO;
import com.kitex.mom.wms.dto.app.WmsMobileStockTraceDTO.Context;
import com.kitex.mom.wms.dto.app.WmsMobileStockTraceDTO.View;
import com.kitex.mom.wms.service.impl.WmsStockTraceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * PDA只读追溯适配：核实名工厂读授权，再沿正式服务的实际仓库权限读取。
 * 不改原库存/预留，不以手机入口扩大证据范围，也不将拣配或当前占用算作正式出库。
 */
@Service
@RequiredArgsConstructor
public class WmsMobileStockTraceService {
	private final WmsMobileWarehouseAccess access;
	private final PermissionService permissions;
	private final WmsStockTraceQueryService traces;

	/**
	 * @return 当前实名组织和已有追溯权限；无工厂read或远端核验失败时明确拒绝
	 */
	public Context context() {
		access.requireFactory(false);
		var scope = WmsAllocationMobileReceiptService.requireScope();
		return new Context(1, scope.tenantId().toString(), scope.factoryCode(), scope.operatorCode(),
				permissions.hasPermission("wms_stockTrace_view"));
	}

	/**
	 * 同一只读数据库快照读取精确库存的WMS证据；授权仓库仍由原追溯服务逐项限定。
	 *
	 * @param stockId 从合法库存详情选择的真实主键，不接受空值或非正标识
	 * @return 本次实际身份、原库存标识及每类最多100条的原证据，保留全部缺口与截断提示
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public View query(Long stockId) {
		if (stockId == null || stockId <= 0) {
			throw new CheckedException("请从库存详情选择要追溯的实物");
		}
		var identity = context();
		if (!identity.canView()) {
			throw new CheckedException("当前账号没有库存追溯查看权限");
		}
		var evidence = traces.query(new WmsStockTraceDTO.Query().setStockId(stockId).setLimit(100));
		if (evidence == null || !Objects.equals(evidence.getFactoryCode(), identity.factoryCode())) {
			throw new CheckedException("库存流转记录未完整读取，请重试");
		}
		return new View(identity, stockId.toString(), evidence);
	}
}
