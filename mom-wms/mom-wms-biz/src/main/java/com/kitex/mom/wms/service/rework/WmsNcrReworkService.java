package com.kitex.mom.wms.service.rework;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsNcrReworkDTO.*;
import com.kitex.mom.wms.service.WmsMobileWarehouseAccess;
import com.kitex.mom.wms.service.bill.WmsBillPermissionProofs;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.kitex.mom.wms.service.rework.WmsNcrReworkRules.*;

/**
 * WMS原位整量返工终态编排。与QMS完成/意图账分域，不写QMS表；库存和永久回执本地原子提交。
 * 真实工厂读写、原仓菜单来源和当前实名人员每次重核，内部入口不能跳过。
 */
@Service
@RequiredArgsConstructor
public class WmsNcrReworkService {
	private final WmsMobileWarehouseAccess factories;
	private final WmsBillPermissionProofs permissions;
	private final WmsNcrReworkRepository repository;
	private final WmsNcrReworkProof proof;
	private final JdbcTemplate db;

	/**
	 * 预检只读原parent精确影响全集，单位仅从这些原行证明，不查今日物料主数据。
	 */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public Source inspect(Key supplied) {
		Key key = key(supplied, true);
		scope(key, false);
		Source source = proof.inspect(key, proof.parents(key, false), false);
		warehouse(source.warehouseCode(), false);
		return source;
	}

	/**
	 * 永久原号只读，未见必须为真实SELECT零行；故障直接抛出。
	 */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public Lookup lookup(Command command) {
		command(command, false);
		scope(command.source().key(), false);
		warehouse(command.source().warehouseCode(), false);
		Receipt saved = repository.find(command);
		if (saved != null) {
			return new Lookup("FOUND", saved);
		}
		proof.frozen(command.source(), false);
		return new Lookup("NOT_FOUND", null);
	}

	/**
	 * 首次apply由原复核人按原文整量办理，另号parent生效位与数据库唯一约束双重保护。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Receipt apply(Command command) {
		return finish(command, false);
	}

	/**
	 * 未见也记永久取消墓碑；与apply同锁，已生效只能返回原事实。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Receipt cancel(Command command) {
		return finish(command, true);
	}

	/**
	 * 原号→395原仓→parent→精确库存升序；获锁后先回永久账，不重验今日库存。
	 * cancel不读取今日库存，已漂移仍可永久封住原号，禁止迟到apply。
	 */
	private Receipt finish(Command c, boolean cancel) {
		command(c);
		scope(c.source().key(), true);
		warehouse(c.source().warehouseCode(), true);
		repository.lock(c);
		Receipt saved = repository.find(c);
		if (saved != null) {
			return saved;
		}
		var pair = proof.frozen(c.source(), true);
		saved = repository.find(c);
		if (saved != null) {
			return saved;
		}
		Actor actor = actor();
		require(actor.userId().equals(c.reviewer().userId()), "只有原独立复核人可续办或取消该决定");
		LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
		if (cancel) {
			return repository.insert(c, "CANCELLED", actor, now, List.of());
		}
		repository.requireUnapplied(c.source().key());
		Source actual = proof.inspect(c.source().key(), pair, true);
		require(actual.equals(c.source()), "原返工片段身份、位置、单位或数量与冻结原文不一致");
		boolean pass = "PASS".equals(c.decision());
		List<Change> changes = actual.pieces().stream().map(p -> new Change(p.stockId(), p.quantity(),
				"1", "REWORK", "UN_QUALIFIED", pass ? "0" : "1", pass ? "QUALIFIED" : "HOLD",
				pass ? "RELEASE" : "UN_QUALIFIED")).toList();
		// 先插结果使DB在旧实物状态下证明原件；延迟约束在提交时核库存已准确迁移。
		Receipt applied = repository.insert(c, "APPLIED", actor, now, changes);
		for (Piece p : actual.pieces()) {
			// 仅原位三元组和owner改变：量、FIFO、位置、物理身份和质量来源均保持原事实。
			int changed = db.update(
					"UPDATE mom_wms.wms_live_stock SET status=?,quality_status=?,quality_result=?,"
							+ "quality_rework_operation_id=NULL WHERE id=? AND tenant_id=? AND factory_code=? "
							+ "AND status=1 AND quality_status='REWORK' AND quality_result='UN_QUALIFIED' "
							+ "AND (quality_rework_operation_id=? OR quality_rework_operation_id IS NULL)",
					pass ? 0 : 1, pass ? "QUALIFIED" : "HOLD", pass ? "RELEASE" : "UN_QUALIFIED",
					id(p.stockId()), id(actual.key().tenantId()), actual.key().factoryCode(),
					id(actual.key().parentOperationId()));
			require(changed == 1, "返工库存终态条件写入失败，已回滚");
		}
		verifyWritten(actual, changes);
		return applied;
	}

	/**
	 * 按每个精确ID回读身份、量、终态及owner清除，任一不符让整笔库存和回执回滚。
	 */
	private void verifyWritten(Source source, List<Change> changes) {
		for (int i = 0; i < source.pieces().size(); i++) {
			Piece before = source.pieces().get(i);
			Change after = changes.get(i);
			var actual = db.queryForList(
					"SELECT status,quality_status,quality_result,quality_rework_operation_id,mat_num "
							+ "FROM mom_wms.wms_live_stock WHERE id=? AND tenant_id=? AND factory_code=?",
					id(before.stockId()), id(source.key().tenantId()), source.key().factoryCode());
			require(actual.size() == 1, "返工库存终态回读缺失");
			var row = actual.get(0);
			require(after.afterStatus().equals(String.valueOf(row.get("status")))
							&& after.afterQuality().equals(row.get("quality_status")) &&
							after.afterResult().equals(row.get("quality_result"))
							&& row.get("quality_rework_operation_id") == null
							&& quantity(before.quantity()).compareTo(
							new java.math.BigDecimal(row.get("mat_num").toString())) == 0,
					"返工库存终态或数量回读不一致");
		}
		// 用完整实体重新证明物理身份和位置；质量三元组只允许上述明确迁移。
		proof.verifyAfter(source, changes);
	}

	/**
	 * 实际组织/人员和MDM准入，不信任body或内部调用头。
	 */
	private void scope(Key k, boolean write) {
		factories.requireFactory(write);
		repository.ready();
		require(Objects.equals(k.tenantId(), String.valueOf(TenantContextHolder.getTenantId()))
						&& Objects.equals(k.factoryCode(), FactoryContextHolder.getFactoryCode()),
				"返工原案组织不属于当前实际范围");
		actor();
	}

	/**
	 * 新返工只使用quality/show或quality/rework真实仓级来源，绝不借特殊收发或旧execute岗位。
	 */
	private void warehouse(String code, boolean write) {
		String action = write ? "rework" : "show";
		String permission = "wmsQualityStatus:" + action;
		permissions.prefetch(List.of(new WmsBillPermissionProofs.Check("quality", action, code, permission)));
		require(permissions.hasProof("quality", action, code, permission),
				"当前账号没有该原仓的质量" + (write ? "返工办理" : "读取") + "权限");
	}

	private Actor actor() {
		var user = SecurityUtils.getUser();
		require(user != null && user.getId() != null && user.getId() > 0 && SecurityUtils.getToken() != null
				&& !SecurityUtils.getToken().isBlank(), "NCR返工必须透传当前实名Bearer");
		Actor actor = new Actor(user.getId().toString(), user.getUsername(), user.getName());
		WmsNcrReworkRules.actor(actor);
		return actor;
	}

	/**
	 * WMS质量页/库存追溯的受权只读摘要；库存查询从永久pieces反查，取消不冒充库存终态。
	 *
	 * @param parentOperationId 原parent，可空；与stockId至少一项
	 * @param stockId           永久影响库存ID，可空
	 */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public List<ResultView> results(String parentOperationId, String stockId) {
		factories.requireFactory(false);
		repository.ready();
		require(parentOperationId != null || stockId != null, "请指定原返工操作或库存");
		List<Object> args = new ArrayList<>(
				List.of(TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode()));
		String sql = "SELECT receipt_json FROM mom_wms.wms_ncr_rework_result WHERE tenant_id=? AND factory_code=?";
		if (parentOperationId != null) {
			sql += " AND parent_operation_id=?";
			args.add(id(parentOperationId));
		}
		if (stockId != null) {
			id(stockId);
			sql += " AND EXISTS (SELECT 1 FROM jsonb_array_elements(command_json::jsonb #> '{source,pieces}') p WHERE p->>'stockId'=?)";
			args.add(stockId);
		}
		sql += " ORDER BY recorded_at,id LIMIT 1001";
		var rows = db.query(sql, (r, n) -> repository.decode(r.getString(1), Receipt.class), args.toArray());
		require(rows.size() <= 1000, "返工永久回执过多，请按原操作缩小范围");
		permissions.prefetch(rows.stream().map(r -> new WmsBillPermissionProofs.Check("quality", "show",
				r.command().source().warehouseCode(), "wmsQualityStatus:show")).toList());
		var views = new ArrayList<ResultView>();
		for (Receipt r : rows) {
			warehouse(r.command().source().warehouseCode(), false);
			require(repository.find(r.command()).equals(r), "返工追溯原文与永久列不一致");
			var c = r.command();
			var k = c.source().key();
			views.add(new ResultView(k.parentOperationId(), c.requestId(), r.status(), c.decision(),
					c.source().warehouseCode(),
					k.quantity(), k.unit(), c.executor() == null ? null : c.executor().name(),
					c.reviewer().name(),
					r.recordedBy().name(), r.recordedAt()));
		}
		return List.copyOf(views);
	}

	/**
	 * @param parentOperationId 原返工操作ID
	 * @param requestId         原独立复核决定请求号
	 * @param status            永久结果APPLIED/CANCELLED
	 * @param decision          原决定PASS/REJECT
	 * @param warehouseCode     实际原仓
	 * @param quantity          原分类整量，单位见unit
	 * @param unit              冻结基本单位
	 * @param executorName      实际完成者，无完成REJECT为空
	 * @param reviewerName      原独立复核人
	 * @param recordedByName    实际WMS结果落账人
	 * @param recordedAt        WMS永久结果时间
	 */
	public record ResultView(String parentOperationId, String requestId, String status, String decision,
	                         String warehouseCode, String quantity, String unit, String executorName,
	                         String reviewerName, String recordedByName, String recordedAt) {
	}
}
