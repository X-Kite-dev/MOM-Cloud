package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.app.WmsMobileFinishedGoodsCorrectionDTO.Context;
import com.kitex.mom.wms.dto.app.WmsMobileFinishedGoodsCorrectionDTO.ContextQuery;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 当前移动原单为单仓合同；所选实际库存必须仍在原通知仓库内。
 */
@Service
@RequiredArgsConstructor
public class WmsFinishedGoodsCorrectionMobileAccess {

	private static final String ADD = "wms_bill_add_in_product";
	private final WmsInNoticeMapper notices;
	private final WmsLiveStockMapper stocks;
	private final PermissionService permissions;
	private final WmsWarehouseBusinessGuard warehouseGuard;

	public void requireScope() {
		var scope = WmsAllocationMobileReceiptService.requireScope();
		require(Objects.equals(SecurityUtils.getUser().getTenantId(), scope.tenantId()),
				"登录租户与当前成品纠错上下文不一致");
	}

	/**
	 * 在读取可用数量等业务依据之前，先确认原通知所属仓库的申请权限。
	 */
	public void requireSource(Long noticeId) {
		requireScope();
		requireNotice(loadNotice(noticeId));
	}

	private WmsInNoticeEntity loadNotice(Long noticeId) {
		var scope = WmsAllocationMobileReceiptService.requireScope();
		require(noticeId != null && noticeId > 0, "请选择原成品入库通知");
		return notices.selectOne(Wrappers.<WmsInNoticeEntity>lambdaQuery()
				.eq(WmsInNoticeEntity::getId, noticeId)
				.eq(WmsInNoticeEntity::getTenantId, scope.tenantId())
				.eq(WmsInNoticeEntity::getFactoryCode, scope.factoryCode()).eq(WmsInNoticeEntity::getDelFlag, "0"), false);
	}

	@Transactional(readOnly = true)
	public Context context(ContextQuery query) {
		requireScope();
		var scope = WmsAllocationMobileReceiptService.requireScope();
		require(query != null && query.noticeId() != null && query.noticeId() > 0
						&& validWarehouse(query.warehouseCode()) && query.stockIds() != null
						&& !query.stockIds().isEmpty() && query.stockIds().size() <= 1000,
				"请从当前仓库选择需要纠错的库存");
		var requested = new TreeSet<Long>();
		for (Long id : query.stockIds()) {
			require(id != null && id > 0 && requested.add(id), "所选库存重复或已失效，请重新选择");
		}
		var notice = loadNotice(query.noticeId());
		requireNotice(notice);
		require(Objects.equals(query.warehouseCode(), notice.getWarehouseCode()), "原通知不属于当前收货仓库");
		var selected = stocks.selectList(Wrappers.<WmsLiveStockEntity>lambdaQuery()
				.in(WmsLiveStockEntity::getId, requested).eq(WmsLiveStockEntity::getTenantId, scope.tenantId())
				.eq(WmsLiveStockEntity::getFactoryCode, scope.factoryCode()).eq(WmsLiveStockEntity::getDelFlag, "0"));
		requireRows(notice, selected);
		var found = new TreeSet<Long>();
		for (var row : selected) {
			found.add(row.getId());
		}
		require(found.equals(requested), "部分所选库存已变化，请重新读取并选择");
		boolean enabled;
		String message;
		try {
			enabled = warehouseGuard.mobileEnabled(notice.getWarehouseCode());
			message = enabled ? "" : "该仓库未开启手机端作业";
		} catch (RuntimeException unavailable) {
			// 仅配置探测降级，实际身份、通知和库存授权均已在捕获范围之外核验。
			enabled = false;
			message = "仓库手机端配置暂不可用，请稍后重试";
		}
		return new Context(1, 1, scope.tenantId().toString(), scope.factoryCode(), scope.operatorCode(),
				notice.getId().toString(), notice.getWarehouseCode(), requested.stream().map(String::valueOf).toList(),
				enabled, message);
	}

	/**
	 * 使用业务核心已锁定的实际选择，必须位于远程质量授权消费之前。
	 */
	public void requireNew(WmsInNoticeEntity notice, List<WmsLiveStockEntity> selected) {
		requireScope();
		requireNotice(notice);
		requireRows(notice, selected);
		warehouseGuard.checkMobile(notice.getWarehouseCode());
	}

	private void requireNotice(WmsInNoticeEntity notice) {
		var scope = WmsAllocationMobileReceiptService.requireScope();
		require(notice != null && notice.getId() != null && notice.getId() > 0
						&& Objects.equals(notice.getTenantId(), scope.tenantId())
						&& Objects.equals(notice.getFactoryCode(), scope.factoryCode()) && "0".equals(notice.getDelFlag())
						&& InBillTypeEnum.PRODUCT.getCode().equals(notice.getBillType()) && validWarehouse(notice.getWarehouseCode()),
				"当前组织下不存在该成品入库通知");
		source(notice);
		require(permissions.hasPermission(ADD) || permissions.hasPermission(ADD + "_" + notice.getWarehouseCode()),
				"当前账号无权提交该仓库成品纠错申请");
	}

	private void requireRows(WmsInNoticeEntity notice, List<WmsLiveStockEntity> selected) {
		var scope = WmsAllocationMobileReceiptService.requireScope();
		require(selected != null && !selected.isEmpty() && selected.size() <= 10000, "所选库存已变化，请重新读取并选择");
		var seen = new HashSet<Long>();
		for (var row : selected) {
			require(row != null && row.getId() != null && row.getId() > 0 && seen.add(row.getId())
							&& Objects.equals(row.getTenantId(), scope.tenantId())
							&& Objects.equals(row.getFactoryCode(), scope.factoryCode()) && "0".equals(row.getDelFlag())
							&& Objects.equals(row.getSourceMesInboundRequestId(), source(notice)),
					"所选库存不属于原成品入库来源，请重新核对");
			require(Objects.equals(row.getWarehouseCode(), notice.getWarehouseCode()),
					"所选库存已移出原收货仓库，请重新选择当前仓库库存");
		}
	}

	private String source(WmsInNoticeEntity notice) {
		String request = notice.getSourceRequestId();
		for (String prefix : List.of("MES_TASK_IN_WMS_V2:", "MES_TASK_IN_WMS:")) {
			if (request != null && request.startsWith(prefix) && request.length() > prefix.length()) {
				return request.substring(prefix.length());
			}
		}
		throw new CheckedException("原通知缺少完整MES成品入库来源，请先核对");
	}

	private boolean validWarehouse(String value) {
		return value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.trim())
				&& !value.contains("*") && !value.contains("?");
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
