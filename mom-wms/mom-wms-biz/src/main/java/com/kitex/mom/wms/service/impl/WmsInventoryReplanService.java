package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsInventoryCancelCommand;
import com.kitex.mom.wms.dto.WmsInventoryReplanCommand;
import com.kitex.mom.wms.dto.WmsInventoryReplanEndCommand;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.mapper.WmsInventoryReplanMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import com.kitex.mom.wms.service.WmsInsideInventoryPlanService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.*;

/**
 * 保留旧实盘；原计划作废、新快照及永久关联同事务提交。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryReplanService {
	private final WmsInventoryPlanAccess access;
	private final WmsInventoryWarehousePermission permissions;
	private final WmsInventoryCancellationService cancellation;
	private final WmsInsideInventoryPlanService plans;
	private final WmsInsideInventoryPlanMapper planMapper;
	private final WmsInventoryReplanMapper records;
	private final WmsWarehouseBusinessGuard mobile;
	private final ObjectMapper json;

	@Transactional(rollbackFor = Exception.class)
	public Context context(Long id) {
		return context(access.plan(id, false));
	}

	private Context context(WmsInsideInventoryPlanEntity source) {
		permissions.require("view", source.getWarehouseCode());
		var user = SecurityUtils.getUser();
		require(user != null && StrUtil.isNotBlank(user.getUsername()) &&
				Objects.equals(user.getTenantId(), TenantContextHolder.getTenantId()), "登录身份与当前租户不一致");
		require(StrUtil.isNotBlank(source.getWarehouseCode()) && StrUtil.isNotBlank(source.getPlanNo()),
				"原计划仓库或编号不完整");
		return new Context(1, source.getTenantId().toString(), factory(), user.getUsername(), source.getWarehouseCode(),
				source.getId().toString(), source.getPlanNo());
	}

	@Transactional(rollbackFor = Exception.class)
	public Prepare prepare(Long id, boolean phone) {
		var source = access.plan(id, true);
		var ctx = context(source);
		var rows = access.details(id, true);
		var scope = scopes(source, rows);
		var winner = records.findSource(source.getTenantId(), source.getFactoryCode(), source.getId());
		boolean allowed = true;
		String message = "";
		try {
			writePermission(source);
			requireSource(source, rows);
			if (phone) {
				mobile.checkMobile(source.getWarehouseCode());
			}
		} catch (CheckedException ex) {
			allowed = false;
			message = ex.getMessage();
		}
		if (winner != null) {
			validate(winner, source);
			allowed = false;
			message = "该计划已重新建单，请查看关联结果";
		}
		return new Prepare(ctx, version(source, scope), mode(source.getStockFreezeMode()),
				Boolean.TRUE.equals(plans.countFreezeAvailable()), allowed, message, scope,
				winner == null ? null : entry(winner));
	}

	@Transactional(rollbackFor = Exception.class)
	public Resolution resolve(Long id, String request) {
		var source = access.plan(id, false);
		context(source);
		return resolve(source, request);
	}

	private Resolution resolve(WmsInsideInventoryPlanEntity source, String request) {
		require(request != null && request.matches("[A-Za-z0-9_-]{16,64}"), "原请求号无效");
		var own = records.findRequest(source.getTenantId(), source.getFactoryCode(), request);
		if (own != null) {
			validate(own, source);
			return new Resolution(1, own.status(), entry(own));
		}
		var winner = records.findSource(source.getTenantId(), source.getFactoryCode(), source.getId());
		if (winner != null) {
			validate(winner, source);
			require("REPLANNED".equals(winner.status()), "原关联结果未核定");
			return new Resolution(1, "ALREADY_REPLANNED", entry(winner));
		}
		return new Resolution(1, "UNSEEN", null);
	}

	@Transactional(rollbackFor = Exception.class)
	public Resolution create(WmsInventoryReplanCommand command, boolean phone) {
		validate(command);
		var observed = access.plan(command.getSourcePlanId(), false);
		var ctx = context(observed);
		var prior = resolve(observed, command.getRequestId());
		if (!"UNSEEN".equals(prior.status())) {
			return replay(prior, command);
		}
		// 与普通建单保持编号→仓库范围锁顺序，再锁源计划，避免相反锁序。
		require(planMapper.lockCountScope("WMS_INVENTORY_PLAN_NO|" + observed.getTenantId() + "|" + factory() + "|" +
				command.getNewPlanNo().trim()) == 1, "锁定新盘点编号失败");
		require(planMapper.lockCountScope(
				"WMS_INVENTORY_PLAN|" + observed.getTenantId() + "|" + factory() + "|" + observed.getWarehouseCode()) ==
				1, "锁定原仓盘点范围失败");
		var source = access.plan(command.getSourcePlanId(), true);
		context(source);
		require(Objects.equals(source.getWarehouseCode(), observed.getWarehouseCode()), "原仓库已变化，请重新读取");
		prior = resolve(source, command.getRequestId());
		if (!"UNSEEN".equals(prior.status())) {
			return replay(prior, command);
		}
		writePermission(source);
		var rows = access.details(source.getId(), true);
		requireSource(source, rows);
		var oldScopes = scopes(source, rows);
		require(command.getSourceVersion().equals(version(source, oldScopes)),
				"原盘点已被更新，请先核对原请求后重新读取");
		if (phone) {
			mobile.checkMobile(source.getWarehouseCode());
		}
		if (!"2".equals(source.getStatus())) {
			var cancel = new WmsInventoryCancelCommand();
			cancel.setId(source.getId());
			cancel.setRequestId(command.getRequestId());
			cancel.setReason(command.getReason().trim());
			require(cancellation.cancel(cancel), "原计划作废失败");
		}
		var target = new WmsInsideInventoryPlanEntity();
		target.setPlanNo(command.getNewPlanNo().trim());
		target.setWarehouseCode(source.getWarehouseCode());
		target.setStockFreezeMode(command.getStockFreezeMode());
		target.setWmsInsideInventoryPlanDetailList(rows.stream().map(row -> {
			var next = new WmsInsideInventoryPlanDetailEntity();
			next.setMatCode(row.getMatCode());
			next.setSiteCode(row.getSiteCode());
			next.setMatBatch(row.getMatBatch());
			next.setMatUnit(row.getMatUnit());
			return next;
		}).toList());
		require(Boolean.TRUE.equals(phone ? plans.saveDeepMobile(target) : plans.saveDeep(target)),
				"新盘点创建失败，原计划变更已回滚");
		require(target.getId() != null && target.getId() > 0 && !source.getId().equals(target.getId()),
				"新盘点原件未可靠保存");
		var newScopes = scopes(target, target.getWmsInsideInventoryPlanDetailList());
		var originalRanges = oldScopes.stream().map(this::range).collect(java.util.stream.Collectors.toSet());
		var targetRanges = newScopes.stream().map(this::range).collect(java.util.stream.Collectors.toSet());
		require(originalRanges.size() == oldScopes.size() && targetRanges.size() == newScopes.size() &&
				originalRanges.equals(targetRanges), "新旧盘点范围不一致，作废和新建均已回滚");
		require(newScopes.stream().allMatch(
				row -> row.countedQuantity() == null && row.lastRecountId() == null && "0".equals(row.status()) &&
						"0".equals(row.plStatus())), "新计划不得带入原实盘或调账结果");
		var audit = new Audit(1, source.getPlanNo(), command.getNewPlanNo().trim(), command.getStockFreezeMode(),
				oldScopes, newScopes);
		var result = row(source, ctx, command, "REPLANNED", target.getId(), encode(audit), null);
		require(records.insert(result) == 1, "关联结果保存失败，作废和新建均已回滚");
		return new Resolution(1, "REPLANNED", entry(result));
	}

	@Transactional(rollbackFor = Exception.class)
	public Resolution end(WmsInventoryReplanEndCommand command) {
		require(command != null, "请提供原重新盘点请求");
		validate(command.getOriginal());
		var original = command.getOriginal();
		require(StrUtil.isNotBlank(command.getReason()) && command.getReason().trim().length() <= 500,
				"请填写500字以内的结束原因");
		var source = access.plan(original.getSourcePlanId(), true);
		var ctx = context(source);
		writePermission(source);
		var prior = resolve(source, original.getRequestId());
		if (!"UNSEEN".equals(prior.status())) {
			return replay(prior, original);
		}
		var result = row(source, ctx, original, "TERMINATED", null, null, command.getReason().trim());
		require(records.insert(result) == 1, "原请求结束凭据未可靠保存");
		return new Resolution(1, "TERMINATED", entry(result));
	}

	private void writePermission(WmsInsideInventoryPlanEntity source) {
		permissions.require("add", source.getWarehouseCode());
		if (!"2".equals(source.getStatus())) {
			permissions.require("cancel", source.getWarehouseCode());
		}
	}

	private void requireSource(WmsInsideInventoryPlanEntity source, List<WmsInsideInventoryPlanDetailEntity> rows) {
		require((Arrays.asList("0", "1").contains(source.getStatus()) && "0".equals(source.getHandleState())) ||
						("2".equals(source.getStatus()) && "2".equals(source.getHandleState())),
				"已办结盘点不能再次作废重建，请新建常规盘点任务");
		if ("2".equals(source.getStatus())) {
			require(StrUtil.isNotBlank(source.getCancelRequestId()) && StrUtil.isNotBlank(source.getCancelReason()) &&
					StrUtil.isNotBlank(source.getCancelBy()) && source.getCancelTime() != null, "原作废依据不完整");
		}
		require(rows != null && !rows.isEmpty(), "原计划没有可重新盘点的范围");
		for (var row : rows) {
			require(row != null && row.getMatCode() != null && row.getMatCode().equals(row.getMatCode().trim())
							&& row.getSiteCode() != null && row.getSiteCode().equals(row.getSiteCode().trim())
							&& (StrUtil.isBlank(row.getMatBatch()) || row.getMatBatch().equals(row.getMatBatch().trim())),
					"原范围编码不规范，请核对后作废原单并按规范范围新建盘点");
		}
		cancellation.requireUnposted(rows);
	}

	private List<Scope> scopes(WmsInsideInventoryPlanEntity plan, List<WmsInsideInventoryPlanDetailEntity> rows) {
		require(rows != null && !rows.isEmpty(), "原盘点范围为空");
		var ids = new HashSet<Long>();
		return rows.stream().map(row -> {
			require(row != null && row.getId() != null && ids.add(row.getId()) &&
					Objects.equals(row.getPlanId(), plan.getId()) &&
					Objects.equals(row.getTenantId(), plan.getTenantId()) &&
					Objects.equals(row.getFactoryCode(), plan.getFactoryCode()) &&
					Objects.equals(row.getWarehouseCode(), plan.getWarehouseCode())
					&& StrUtil.isNotBlank(row.getMatCode()) && StrUtil.isNotBlank(row.getSiteCode()) &&
					StrUtil.isNotBlank(row.getMatUnit()), "盘点明细身份或范围不完整");
			return new Scope(row.getId().toString(), plan.getId().toString(), row.getMatCode(), row.getMatName(),
					row.getSiteCode(), row.getSiteName(), StrUtil.blankToDefault(row.getMatBatch(), null),
					row.getMatUnit(), number(row.getCurrentNumber(), "账面数量").stripTrailingZeros().toPlainString(),
					row.getInventoryNum() == null ? null : number(row.getInventoryNum(),
							"实盘数量").stripTrailingZeros().toPlainString(), row.getStatus(), row.getPlStatus(),
					row.getLastRecountId() == null ? null : row.getLastRecountId().toString());
		}).sorted(Comparator.comparing(row -> new java.math.BigInteger(row.id()))).toList();
	}

	private Range range(Scope row) {
		return new Range(row.matCode(), row.siteCode(), row.matBatch());
	}

	private record Range(String material, String site, String batch) {
	}

	private String version(WmsInsideInventoryPlanEntity plan, List<Scope> rows) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(
					Arrays.asList(plan.getTenantId().toString(), plan.getFactoryCode(), plan.getId().toString(),
							plan.getPlanNo(), plan.getWarehouseCode(), plan.getStatus(), plan.getHandleState(),
							mode(plan.getStockFreezeMode()), plan.getCancelRequestId(), rows)).getBytes(
					StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String encode(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new CheckedException("保存重新盘点依据失败");
		}
	}

	private String mode(String value) {
		return StrUtil.isBlank(value) ? "SNAPSHOT" : value;
	}

	private void validate(WmsInventoryReplanCommand command) {
		require(command != null && command.getSourcePlanId() != null && command.getSourcePlanId() > 0,
				"请选择原盘点计划");
		require(command.getRequestId() != null && command.getRequestId().matches("[A-Za-z0-9_-]{16,64}"),
				"重新盘点请求号无效");
		require(command.getSourceVersion() != null && command.getSourceVersion().matches("[a-f0-9]{64}"),
				"原盘点版本不完整");
		require(StrUtil.isNotBlank(command.getNewPlanNo()) && command.getNewPlanNo().trim().length() <= 32,
				"请填写32字以内的新盘点编号");
		require(StrUtil.isNotBlank(command.getReason()) && command.getReason().trim().length() <= 500,
				"请填写500字以内的重新盘点原因");
		require(Arrays.asList("SNAPSHOT", "FREEZE_COUNT").contains(command.getStockFreezeMode()),
				"请选择有效的盘点库存模式");
	}

	private void validate(WmsInventoryReplanMapper.Row row, WmsInsideInventoryPlanEntity source) {
		require(Objects.equals(row.tenantId(), source.getTenantId()) &&
				Objects.equals(row.factoryCode(), source.getFactoryCode()) &&
				Objects.equals(row.sourcePlanId(), source.getId()) &&
				Objects.equals(row.warehouseCode(), source.getWarehouseCode()) &&
				Objects.equals(row.sourcePlanNo(), source.getPlanNo()) &&
				Arrays.asList("REPLANNED", "TERMINATED").contains(row.status()), "原请求关联范围或状态不一致");
	}

	private Resolution replay(Resolution result, WmsInventoryReplanCommand command) {
		if (!"ALREADY_REPLANNED".equals(result.status())) {
			var r = result.entry();
			require(Objects.equals(r.sourceVersion(), command.getSourceVersion()) &&
					Objects.equals(r.newPlanNo(), command.getNewPlanNo().trim()) &&
					Objects.equals(r.stockFreezeMode(), command.getStockFreezeMode()) &&
					Objects.equals(r.reason(), command.getReason().trim()), "原请求已用于不同的重新盘点内容");
		}
		return result;
	}

	private WmsInventoryReplanMapper.Row row(WmsInsideInventoryPlanEntity source, Context ctx,
	                                         WmsInventoryReplanCommand c, String status, Long target, String audit,
	                                         String endReason) {
		return new WmsInventoryReplanMapper.Row(source.getTenantId(), source.getFactoryCode(), c.getRequestId(),
				source.getId(), source.getWarehouseCode(), source.getPlanNo(), c.getSourceVersion(),
				c.getNewPlanNo().trim(), c.getStockFreezeMode(), c.getReason().trim(), status, target, audit,
				ctx.operatorCode(), endReason, LocalDateTime.now());
	}

	private Entry entry(WmsInventoryReplanMapper.Row r) {
		return new Entry(r.tenantId().toString(), r.factoryCode(), r.requestId(), r.sourcePlanId().toString(),
				r.warehouseCode(), r.sourceVersion(), r.newPlanNo(), r.stockFreezeMode(), r.reason(), r.status(),
				r.targetPlanId() == null ? null : r.targetPlanId().toString(), r.auditJson(), r.operatorCode(),
				r.endReason(), r.operatedTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
	}

	public record Context(int contractVersion, String tenantId, String factoryCode, String operatorCode,
	                      String warehouseCode, String sourcePlanId, String sourcePlanNo) {
	}

	public record Scope(String id, String planId, String matCode, String matName, String siteCode, String siteName,
	                    String matBatch, String matUnit, String bookQuantity, String countedQuantity, String status,
	                    String plStatus, String lastRecountId) {
	}

	public record Prepare(Context context, String sourceVersion, String stockFreezeMode, boolean freezeAvailable,
	                      boolean writeAllowed, String message, List<Scope> rows, Entry replacement) {
	}

	public record Entry(String tenantId, String factoryCode, String requestId, String sourcePlanId,
	                    String warehouseCode, String sourceVersion, String newPlanNo, String stockFreezeMode,
	                    String reason, String status, String targetPlanId, String auditJson, String operatorCode,
	                    String endReason, String operatedTime) {
	}

	public record Resolution(int contractVersion, String status, Entry entry) {
	}

	public record Audit(int version, String sourcePlanNo, String targetPlanNo, String stockFreezeMode,
	                    List<Scope> sourceRows, List<Scope> targetRows) {
	}
}
