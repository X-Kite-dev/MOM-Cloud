package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.mapper.WmsInventoryCountScheduleMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/** 一个到期时段对应一个事务：计划和下次执行时刻共同提交或共同回滚。 */
@Service
@RequiredArgsConstructor
public class WmsInventoryCountScheduleRunService {
	private final WmsInventoryCountScheduleMapper schedules;
	private final WmsWarehouseMenuMapper menus;
	private final WmsInventoryCountTemplateService templates;

	@Transactional(rollbackFor = Exception.class)
	public boolean run(Long tenant, String factory, WmsInventoryCountScheduleMapper.DueRow candidate,
			LocalDateTime nowUtc) {
		require(tenant != null && tenant > 0 && StrUtil.isNotBlank(factory)
				&& Objects.equals(tenant, TenantContextHolder.getTenantId())
				&& Objects.equals(factory, FactoryContextHolder.getFactoryCode())
				&& !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
				"盘点定时任务必须具有明确的租户和工厂上下文");
		require(candidate != null && StrUtil.isNotBlank(candidate.templateCode())
				&& StrUtil.isNotBlank(candidate.warehouseCode())
				&& candidate.nextRunUtc() != null && nowUtc != null
				&& !candidate.nextRunUtc().isAfter(nowUtc),
				"盘点任务到期候选无效");
		menus.lockConfiguration("WMS_WAREHOUSE_MENU:" + tenant + ":" + factory);
		var current = schedules.findForUpdate(tenant, factory,
				candidate.templateCode(), candidate.warehouseCode());
		if (current == null || !current.enabled()
				|| current.version() != candidate.version()
				|| !candidate.nextRunUtc().equals(current.nextRunUtc())) {
			return false;
		}
		LocalDateTime next = WmsInventoryCountScheduleService.nextDueFromSlot(
				candidate.nextRunUtc(), nowUtc, current.intervalDays(),
				current.timeOfDay(), current.zoneId());
		var result = templates.triggerScheduled(candidate.warehouseCode(),
				candidate.templateCode(), candidate.nextRunUtc());
		require(schedules.advance(tenant, factory, candidate.templateCode(),
				current.version(), candidate.nextRunUtc(), next) == 1,
				"盘点周期推进失败，计划创建已回滚");
		return result != null;
	}
}
