package com.kitex.mom.wms.service.physical;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsQualityPhysicalDTO.*;
import com.kitex.mom.wms.entity.WmsQualityStatusOperationEntity;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.special.WmsSpecialReturnQualityProofService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.wms.service.physical.WmsPhysicalRules.*;

/**
 * 原operation一把行锁串行裁决原号终态、整件归档及实际执行人。
 */
@Service
@RequiredArgsConstructor
public class WmsPhysicalService {
	private final WmsQualityStatusOperationMapper operations;
	private final WmsLiveStockMapper stocks;
	private final WmsPhysicalRepository repository;
	private final WmsPhysicalProof proof;
	private final WmsPhysicalAccess access;
	private final WmsPhysicalJson json;
	private final WmsSpecialReturnQualityProofService special;

	/**
	 * 全部原仓先SQL授权，再count/order/分页；未授权整单不进入结果。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public IPage<PageRow> page(Page<?> page, WmsQualityStatusOperationEntity query, String barcode) {
		access.read();
		require(page.getCurrent() > 0 && page.getSize() > 0 && page.getSize() <= 1000, "分页范围无效");
		var candidates = repository.candidates(tenant(), factory());
		var allowed = access.proved(candidates, "show");
		access.proved(candidates, "execute");
		access.proved(candidates, "retry");
		boolean factoryWrite = access.canWrite();
		long[] total = {
				0};
		var ids = repository.page(tenant(), factory(), allowed, query, barcode,
				(page.getCurrent() - 1) * page.getSize(), page.getSize(), true, total);
		List<PageRow> rows = new ArrayList<>();
		for (Long id : ids) {
			rows.add(detail(load(id, false), false, factoryWrite).operation());
		}
		return new Page<PageRow>(page.getCurrent(), page.getSize(), total[0]).setRecords(rows);
	}

	/**
	 * 仅首次详情生成建议号，不预占原号，actor始终为当前实名。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Detail receipt(String operationId) {
		access.read();
		return detail(load(id(operationId), false), true);
	}

	/**
	 * 按真实租户工厂精确读取原operation；lock为true时取唯一父行锁，不存在即拒绝。
	 */
	public WmsQualityStatusOperationEntity load(Long id, boolean lock) {
		var q = new LambdaQueryWrapper<WmsQualityStatusOperationEntity>().eq(
						WmsQualityStatusOperationEntity::getTenantId, tenant())
				.eq(WmsQualityStatusOperationEntity::getFactoryCode, factory())
				.eq(WmsQualityStatusOperationEntity::getId, id);
		if (lock) {
			q.last("FOR UPDATE");
		}
		var o = operations.selectOne(q, false);
		require(o != null, "原质量操作不存在或不属于当前组织");
		return o;
	}

	/**
	 * 使用与分页相同完整证据关系证明全部原仓；无证据不允许泄漏详情。
	 */
	public List<String> warehouses(WmsQualityStatusOperationEntity o) {
		var ws = repository.originalWarehouses(o.getTenantId(), o.getFactoryCode(), o.getId());
		require(!ws.isEmpty(), "无法证明完整原仓，须核对原操作");
		return ws;
	}

	/**
	 * lookup只读终态；缺章、无权、损坏、对象不存在都不是OPEN。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Lookup lookup(Command c) {
		author(c, false);
		repository.ready();
		var o = load(id(c.operationId()), false);
		access.requireWarehouses(warehouses(o), "show");
		proof.parent(c.source(), o);
		return resolve(c, o, repository.find(c));
	}

	/**
	 * cancel不比对今日实存Source；只裁本人原号，不做库存补偿。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Lookup cancel(Command c) {
		return write(c, false);
	}

	/**
	 * 首次办理只有此入口，同号命中先返回永久事实，不读取今日active实存。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Lookup apply(Command c) {
		return write(c, true);
	}

	private Lookup write(Command c, boolean apply) {
		author(c, true);
		repository.ready();
		var observed = load(id(c.operationId()), false);
		List<String> ws;
		try {
			ws = warehouses(observed);
		} catch (CheckedException e) {
			require(!apply, "原仓无法证明，不能执行实物");
			return cancelOwn(c, observed);
		}
		if (!apply && !access.allowed(ws, "show")) {
			return cancelOwn(c, observed);
		}
		access.requireWarehouses(ws, "show");
		access.requireWarehouses(ws, "execute");
		if (observed.getSpecialReturnBindingJson() != null) {
			special.authorizeOperation(observed);
		}
		repository.warehouses(tenant(), factory(), ws);
		var o = load(id(c.operationId()), true);
		proof.parent(c.source(), o);
		access.requireWarehouses(warehouses(o), "execute");
		var prior = repository.find(c);
		if (prior != null || "EXECUTED".equals(o.getStatus())) {
			return resolve(c, o, prior);
		}
		if (!apply) {
			var r = repository.insert(c, "CANCELLED", SecurityUtils.getUser().getUsername(),
					SecurityUtils.getUser().getName());
			return resolve(c, o, r);
		}
		require(superseded(o) == null, "原处置已被成功重隔离永久撤销");
		Source current = proof.inspect(o, true, false);
		require(current.equals(c.source()), "原实物完整Source已变化，请结束本号后重新读取");
		var user = SecurityUtils.getUser();
		Receipt r = repository.insert(c, "APPLIED", user.getUsername(), user.getName());
		repository.archive(c);
		o.setStatus("EXECUTED").setExecutionRequestId(c.requestId()).setExecutionRef(c.executionRef())
				.setExecutionRemark(c.remark()).setExecutedBy(r.endedBy()).setExecutedByName(r.endedByName())
				.setExecutedTime(LocalDateTime.parse(r.endedTime()))
				.setResultMessage("质量实物出库已确认，执行单号=" + c.executionRef());
		require(operations.updateById(o) == 1, "实物原操作执行回执保存失败");
		return resolve(c, load(o.getId(), false), r);
	}

	/**
	 * 只否决当前实名自己的未来原号；没有show时绝不返回业务详情或他号结果。
	 */
	private Lookup cancelOwn(Command c, WmsQualityStatusOperationEntity observed) {
		proof.parent(c.source(), observed);
		var declared = c.source().pieces().stream().map(Piece::warehouseCode).distinct().sorted().toList();
		access.requireWarehouses(declared, "execute");
		repository.warehouses(tenant(), factory(), declared);
		var locked = load(id(c.operationId()), true);
		proof.parent(c.source(), locked);
		var prior = repository.find(c);
		require(prior == null || prior.command().equals(c), "原号已被不同原文占用");
		if (prior != null) {
			require("CANCELLED".equals(prior.outcome()), "原号不能登记取消，请恢复原仓读取权限后核对");
			// 本人同文取消已成为永久事实；父以后他号执行不能改变本号终态。
			return new Lookup(1, c.operationId(), c.requestId(), "CANCELLED", prior, null);
		}
		require(!"EXECUTED".equals(locked.getStatus()), "原号不能登记取消，请恢复原仓读取权限后核对");
		var ended = repository.insert(c, "CANCELLED", SecurityUtils.getUser().getUsername(),
				SecurityUtils.getUser().getName());
		return new Lookup(1, c.operationId(), c.requestId(), "CANCELLED", ended, null);
	}

	private void author(Command c, boolean write) {
		command(c);
		if (write) {
			access.write();
		} else {
			access.read();
		}
		require(c.actorId().equals(SecurityUtils.getUser().getId().toString()) &&
				c.source().key().tenantId().equals(tenant().toString()) &&
				c.source().key().factoryCode().equals(factory()), "命令实名或组织与当前身份不一致");
	}

	private Lookup resolve(Command c, WmsQualityStatusOperationEntity o, Receipt r) {
		String outcome;
		if (r != null) {
			outcome = r.command().equals(c) ? r.outcome() : "CONFLICT";
			if ("CONFLICT".equals(outcome)) {
				r = null;
			}
		} else if ("EXECUTED".equals(o.getStatus())) {
			outcome = appliedExecution(o) != null ? "EXECUTED_OTHER" : "LEGACY_EXECUTED";
		} else {
			outcome = "OPEN";
		}
		return new Lookup(1, c.operationId(), c.requestId(), outcome, r, detail(o, false));
	}

	/**
	 * 纯读确证普通父关联或395同target/root且严格序号的成功REHOLD。
	 */
	public String superseded(WmsQualityStatusOperationEntity o) {
		return o.getSpecialReturnBindingJson() == null ? repository.superseded(
				o) : special.readSupersededByOperationId(o);
	}

	private Detail detail(WmsQualityStatusOperationEntity o, boolean suggest) {
		return detail(o, suggest, access.canWrite());
	}

	/**
	 * 分页复用本次真实MDM写权结果，仓菜单证明仍按原仓全集逐项使用。
	 */
	private Detail detail(WmsQualityStatusOperationEntity o, boolean suggest, boolean factoryWrite) {
		List<String> ws = warehouses(o);
		access.requireWarehouses(ws, "show");
		String replacement = superseded(o);
		boolean executed = "EXECUTED".equals(o.getStatus());
		Source source =
				o.getPhysicalSourceJson() == null ? null : json.decode(o.getPhysicalSourceJson(), Source.class);
		String blocked = null;
		boolean executable = false;
		boolean physical = Set.of("SCRAP", "RETURN").contains(o.getAction());
		if (physical && "ACTION_PENDING".equals(o.getStatus()) && replacement == null) {
			try {
				Source current = proof.inspect(o, false, false);
				if (source == null) {
					source = current;
				}
				require(current.equals(source), "原实物与冻结来源已变化");
				executable = true;
			} catch (CheckedException e) {
				blocked = e.getMessage();
			}
		} else {
			blocked = executed ? "原实物已执行" :
					replacement != null ? "原处置已永久重隔离" : "当前状态不允许首次实物执行";
		}
		boolean write = factoryWrite;
		executable = executable && write && access.allowed(ws, "execute");
		if (!executable && blocked == null) {
			blocked = "当前人员没有全部原仓实物执行权限";
		}
		boolean retry = "PENDING".equals(o.getStatus()) && write && access.allowed(ws, "retry");
		Receipt applied = executed ? appliedExecution(o) : null;
		if (applied != null) {
			require(applied != null && applied.command().operationId().equals(o.getId().toString()) &&
							applied.command().requestId().equals(o.getExecutionRequestId()) &&
							applied.command().executionRef().equals(o.getExecutionRef()) &&
							applied.command().remark().equals(o.getExecutionRemark()) &&
							Objects.equals(applied.endedBy(), o.getExecutedBy()) &&
							Objects.equals(applied.endedByName(), o.getExecutedByName()) &&
							Objects.equals(applied.endedTime(),
									time(o.getExecutedTime())),
					"永久生效回执与原操作执行事实不一致");
		}
		if (applied != null && source == null) {
			source = applied.command().source();
		}
		Execution execution = executed ? new Execution(o.getExecutionRequestId(), o.getExecutionRef(),
				o.getExecutionRemark(), applied == null ? null : applied.endedById(), o.getExecutedBy(),
				o.getExecutedByName(),
				time(o.getExecutedTime())) : null;
		String basis =
				o.getPhysicalSourceJson() != null ? "FROZEN_SOURCE" : applied != null ? "APPLIED_RECEIPT" :
						o.getSpecialReturnBindingJson() != null ? "SPECIAL_BINDING" :
								o.getAffectedStockIds() != null &&
										!o.getAffectedStockIds().isEmpty() ? "EXACT_STOCK_IDS" :
										o.getWarehouseCode() != null &&
												!o.getWarehouseCode()
														.isEmpty() ? "COMMAND_WAREHOUSE" : "PENDING_CANDIDATES";
		List<String> affected =
				o.getAffectedStockIds() == null || o.getAffectedStockIds().isEmpty() ? List.of() : proof.ids(
						o.getAffectedStockIds()).stream().map(String::valueOf).toList();
		PageRow row = new PageRow(o.getId().toString(), o.getTenantId().toString(), o.getFactoryCode(),
				o.getRequestId(), o.getHoldNo(), o.getSourceType(), o.getSourceId(), o.getAction(),
				o.getMatCode(), o.getMatBatch(), amount(o.getRequestedQuantity()),
				amount(o.getAffectedQuantity()), o.getMatUnit(), affected, ws, basis, o.getStatus(),
				replacement != null ? "SUPERSEDED" : executed ? "EXECUTED" : "UNVERIFIED", replacement,
				o.getResultMessage(), o.getExecutionRef(),
				time(o.getCreateTime()), executable, retry);
		String viewer = SecurityUtils.getUser().getId().toString();
		return new Detail(1, viewer, row,
				o.getPhysicalSourceJson() != null ||
						applied != null ? "FROZEN" : executed ? "LEGACY_PARTIAL" : "OBSERVED", source,
				applied, execution, blocked,
				suggest && executable ? "PX1:" + viewer + ":" + UUID.randomUUID() : null);
	}

	/**
	 * 新旧按真实永久APPLIED及冻结事实区分，历史原号可包含任意合法前缀。
	 */
	public Receipt appliedExecution(WmsQualityStatusOperationEntity o) {
		Receipt applied = repository.applied(o);
		require(o.getPhysicalSourceJson() == null || applied != null, "已冻结的新实物执行缺少永久APPLIED账");
		return applied;
	}

	private Long tenant() {
		return TenantContextHolder.getTenantId();
	}

	private String factory() {
		return FactoryContextHolder.getFactoryCode();
	}
}
