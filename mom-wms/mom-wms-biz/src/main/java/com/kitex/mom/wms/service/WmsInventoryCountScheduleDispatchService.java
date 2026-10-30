package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.mapper.WmsInventoryCountScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/** 显式租户工厂的一轮周期扫描；每个到期模板由独立事务提交。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WmsInventoryCountScheduleDispatchService {
	private final WmsInventoryCountScheduleMapper schedules;
	private final WmsInventoryCountScheduleRunService runner;

	public Summary runScope(Long tenant, String factory, int limit) {
		return runScopeAt(tenant, factory, limit, LocalDateTime.now(ZoneOffset.UTC));
	}

	Summary runScopeAt(Long tenant, String factory, int limit, LocalDateTime nowUtc) {
		require(tenant != null && tenant > 0 && StrUtil.isNotBlank(factory)
				&& limit >= 1 && limit <= 100 && nowUtc != null
				&& !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
				"盘点定时任务必须指定租户、工厂和1至100条扫描上限");
		Long previousTenant = TenantContextHolder.getTenantId();
		String previousFactory = FactoryContextHolder.getFactoryCode();
		try {
			TenantContextHolder.setTenantId(tenant);
			FactoryContextHolder.setFactoryCode(factory);
			List<WmsInventoryCountScheduleMapper.DueRow> due = schedules.due(tenant, factory, nowUtc, limit);
			require(due != null && due.size() <= limit, "盘点定时任务到期范围无效");
			Set<String> seen = new HashSet<>();
			for (var row : due) {
				require(row != null && StrUtil.isNotBlank(row.templateCode())
						&& StrUtil.isNotBlank(row.warehouseCode())
						&& row.nextRunUtc() != null && !row.nextRunUtc().isAfter(nowUtc)
						&& row.version() >= 1 && seen.add(row.templateCode()),
						"盘点定时任务到期列表存在重复或异常范围");
			}
			int created = 0;
			int failed = 0;
			for (var row : due) {
				try {
					if (runner.run(tenant, factory, row, nowUtc)) {
						created++;
					}
				} catch (RuntimeException exception) {
					failed++;
					log.error("盘点周期执行失败 tenant={} factory={} template={} dueUtc={}",
							tenant, factory, row.templateCode(), row.nextRunUtc(), exception);
				}
			}
			return new Summary(due.size(), created, failed);
		} finally {
			TenantContextHolder.setTenantId(previousTenant);
			FactoryContextHolder.setFactoryCode(previousFactory);
		}
	}

	public record Summary(int scanned, int created, int failed) {
	}
}
