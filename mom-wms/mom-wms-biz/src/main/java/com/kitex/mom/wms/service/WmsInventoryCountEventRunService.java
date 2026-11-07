package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.mapper.WmsInventoryCountEventMapper;
import com.kitex.mom.wms.mapper.WmsInventoryCountTemplateMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 一个事件一笔事务；失败登记另事务，永不覆盖已提交的永久结果。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryCountEventRunService {
	private final WmsInventoryCountEventMapper events;
	private final WmsInventoryCountTemplateMapper templateRows;
	private final WmsInventoryCountTemplateService templates;
	private final WmsWarehouseMenuMapper menus;
	private final ObjectMapper json;

	@Transactional(rollbackFor = Exception.class)
	public String run(Long tenant, String factory, WmsInventoryCountEventMapper.Candidate c, String actor) {
		validate(tenant, factory, c, actor);
		lock(tenant, factory);
		var prior = events.result(tenant, factory, c.warehouseCode(), c.templateCode(), c.eventId());
		if (prior != null && !"FAILED".equals(prior.status())) {
			require(("CREATED".equals(prior.status()) || "SKIPPED".equals(prior.status())), "原事件结果状态无效");
			return "EXISTING";
		}
		var p = events.lockPolicy(tenant, factory, c.warehouseCode(), c.templateCode());
		var e = events.event(tenant, factory, c.warehouseCode(), c.eventId());
		if (!eligible(p, e, tenant, factory, c)) {
			return "PAUSED";
		}
		var t = templateRows.findForUpdate(tenant, factory, c.templateCode());
		require(t != null && c.warehouseCode().equals(t.warehouseCode()), "事件模板范围已变化");
		if (!t.enabled()) {
			return "PAUSED";
		}

		var evidence = new FailureEvidence(tenant, factory, c.warehouseCode(), c.templateCode(), c.eventId(),
				p.version(), write(p), write(t), actor);
		try {
			String request = "EVT_" + UUID.nameUUIDFromBytes(
					(tenant + ":" + factory.length() + ":" + factory + ":" + c.templateCode() + ":" +
							c.eventId()).getBytes(
							StandardCharsets.UTF_8));
			var result = templates.triggerEvent(c.warehouseCode(), c.templateCode(), request,
					new WmsInventoryCountTemplateService.EventScope(e.matCode(), e.siteCode(), e.matBatch()));
			String status = result == null ? "SKIPPED" : "CREATED";
			require(events.record(tenant, factory, c.warehouseCode(), c.templateCode(), c.eventId(), status,
							result == null ? null : result.planId(), result == null ? null : result.planNo(),
							result == null ? "当前模板无匹配库存" : "已生成盘点计划", evidence.policyVersion(),
							evidence.policyJson(), evidence.templateJson(), actor) == 1,
					"事件结果登记失败，盘点创建已回滚");
			return status;
		} catch (RuntimeException failure) {
			throw new AttemptFailure(failure, evidence);
		}

	}

	@Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
	public void failed(AttemptFailure failure) {
		require(failure != null, "缺少原事件执行依据");
		var evidence = failure.evidence;
		var c = new WmsInventoryCountEventMapper.Candidate(evidence.templateCode(), evidence.warehouse(),
				evidence.eventId());
		validate(evidence.tenant(), evidence.factory(), c, evidence.actor());
		lock(evidence.tenant(), evidence.factory());
		String reason = StrUtil.blankToDefault(failure.getMessage(), "处理失败，请核对执行日志");
		if (reason.length() > 400) {
			reason = reason.substring(0, 400);
		}
		int written = events.record(evidence.tenant(), evidence.factory(), evidence.warehouse(),
				evidence.templateCode(), evidence.eventId(),
				"FAILED", null, null, reason, evidence.policyVersion(), evidence.policyJson(), evidence.templateJson(),
				evidence.actor());
		require(written == 0 || written == 1, "原事件失败履历保存未确认");
	}

	private record FailureEvidence(Long tenant, String factory, String warehouse, String templateCode, Long eventId,
	                               int policyVersion, String policyJson, String templateJson, String actor) {
	}

	public static final class AttemptFailure extends CheckedException {
		private static final long serialVersionUID = 1L;
		private final FailureEvidence evidence;

		private AttemptFailure(RuntimeException cause, FailureEvidence evidence) {
			super(cause instanceof CheckedException ? cause.getMessage() : "处理失败，请联系管理员核对执行日志");
			this.evidence = evidence;
			initCause(cause);
		}
	}

	private void validate(Long tenant, String factory, WmsInventoryCountEventMapper.Candidate c, String actor) {
		require(tenant != null && tenant > 0 && Objects.equals(tenant, TenantContextHolder.getTenantId())
				&& StrUtil.isNotBlank(factory) && Objects.equals(factory, FactoryContextHolder.getFactoryCode())
				&& !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip()) &&
				!Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()), "事件盘点须指定明确租户和工厂");
		require(c != null && c.eventId() != null && c.eventId() > 0 && StrUtil.isNotBlank(c.warehouseCode())
				&& c.templateCode() != null && c.templateCode().matches("[A-Z][A-Z0-9_]{1,31}") &&
				StrUtil.isNotBlank(actor) && actor.length() <= 100, "事件盘点候选无效");
	}

	private boolean eligible(WmsInventoryCountEventMapper.PolicyRow p, WmsInventoryCountEventMapper.EventRow e,
	                         Long tenant, String factory, WmsInventoryCountEventMapper.Candidate c) {
		if (p == null || !p.enabled()) {
			return false;
		}
		require(p.version() > 0 && p.startEventId() != null && p.startEventId() > 0, "事件规则边界无效");
		require(e != null && Objects.equals(e.id(), c.eventId()) && Objects.equals(e.tenantId(), tenant)
				&& factory.equals(e.factoryCode()) && c.warehouseCode().equals(e.warehouseCode())
				&& StrUtil.isNotBlank(e.warningNo()) && e.warningId() != null && e.warningId() > 0
				&& canonical(e.matCode()) && canonical(e.siteCode()) && canonical(e.matUnit())
				&& (StrUtil.isBlank(e.matBatch()) || canonical(e.matBatch())), "预警事件原件范围无效");
		return e.id() > p.startEventId() && ((p.expiration() && "EXPIRATION".equals(e.eventType()))
				|| (p.slowMoving() && "SLOW_MOVING".equals(e.eventType())));
	}

	private boolean canonical(String value) {
		return StrUtil.isNotBlank(value) && value.equals(value.trim());
	}

	private void lock(Long tenant, String factory) {
		menus.lockConfiguration("WMS_WAREHOUSE_MENU:" + tenant + ":" + factory);
	}

	private String write(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new CheckedException("事件处理依据生成失败");
		}
	}
}
