package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.mapper.WmsInventoryCountEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 按组织分批消费；手动处理仍逐模板核验真实仓库权限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WmsInventoryCountEventDispatchService {
	private final WmsInventoryCountEventMapper events;
	private final WmsInventoryCountEventRunService runner;
	private final WmsInventoryCountEventConfigService configs;

	public Summary process(String warehouse, String code) {
		var s = configs.scope(warehouse, code);
		var u = SecurityUtils.getUser();
		require(u != null && StrUtil.isNotBlank(u.getUsername()), "无法识别当前操作人");
		return scan(s.tenant(), s.factory(), warehouse, code, true, 20, u.getUsername());
	}

	public String retry(String warehouse, String code, String eventId) {
		var s = configs.scope(warehouse, code);
		require(eventId != null && eventId.matches("[1-9][0-9]{0,18}"), "请选择原预警事件");
		long id;
		try {
			id = Long.parseLong(eventId);
		} catch (NumberFormatException ex) {
			throw new CheckedException("预警事件标识无效");
		}
		var u = SecurityUtils.getUser();
		require(u != null && StrUtil.isNotBlank(u.getUsername()), "无法识别当前操作人");
		var c = new WmsInventoryCountEventMapper.Candidate(code, warehouse, id);
		try {
			return runner.run(s.tenant(), s.factory(), c, u.getUsername());
		} catch (RuntimeException ex) {
			if (ex instanceof WmsInventoryCountEventRunService.AttemptFailure failure) {
				runner.failed(failure);
			}
			throw ex;
		}
	}

	public Summary runScope(Long tenant, String factory, int limit) {
		require(tenant != null && tenant > 0 && StrUtil.isNotBlank(factory) && factory.equals(factory.trim()) &&
						limit >= 1 && limit <= 100
						&& !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip()) &&
						!Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
				"事件盘点任务须指定租户、工厂及1至100条上限");
		var oldTenant = TenantContextHolder.getTenantId();
		var oldFactory = FactoryContextHolder.getFactoryCode();
		try {
			TenantContextHolder.setTenantId(tenant);
			FactoryContextHolder.setFactoryCode(factory);
			return scan(tenant, factory, null, null, false, limit, "WMS_EVENT");
		} finally {
			TenantContextHolder.setTenantId(oldTenant);
			FactoryContextHolder.setFactoryCode(oldFactory);
		}
	}

	private Summary scan(Long tenant, String factory, String warehouse, String code, boolean force, int limit,
	                     String actor) {
		var candidates = events.pending(tenant, factory, warehouse, code, force, limit);
		require(candidates != null && candidates.size() <= limit, "事件盘点候选读取失败");
		var seen = new HashSet<String>();
		for (var c : candidates) {
			require(c != null && c.eventId() != null && c.eventId() > 0 && StrUtil.isNotBlank(c.warehouseCode())
					&& c.templateCode() != null && c.templateCode().matches("[A-Z][A-Z0-9_]{1,31}")
					&& (warehouse == null || warehouse.equals(c.warehouseCode())) &&
					(code == null || code.equals(c.templateCode()))
					&& seen.add(c.templateCode() + ":" + c.eventId()), "事件盘点候选范围异常");
		}
		int created = 0, skipped = 0, failed = 0, other = 0;
		for (var c : candidates) {
			try {
				String state = runner.run(tenant, factory, c, actor);
				switch (state) {
					case "CREATED" -> created++;
					case "SKIPPED" -> skipped++;
					default -> other++;
				}
			} catch (RuntimeException ex) {
				failed++;
				log.error("预警转盘点失败 tenant={} factory={} template={} event={}", tenant, factory, c.templateCode(),
						c.eventId(), ex);
				// 登记失败亦报失败，不能掩盖为成功；未终结原事件仍可在下一轮重试。
				try {
					if (ex instanceof WmsInventoryCountEventRunService.AttemptFailure failure) {
						runner.failed(failure);
					}
				} catch (RuntimeException audit) {
					log.error("事件失败原因保存失败", audit);
				}
			}
		}
		return new Summary(candidates.size(), created, skipped, failed, other);
	}


	public record Summary(int scanned, int created, int skipped, int failed, int unchanged) {
	}
}
