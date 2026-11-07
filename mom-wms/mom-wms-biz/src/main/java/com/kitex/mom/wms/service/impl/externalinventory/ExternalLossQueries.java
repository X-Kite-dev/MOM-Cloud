package com.kitex.mom.wms.service.impl.externalinventory;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryAccess.Identity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryCodec.id;
import static com.kitex.mom.wms.service.impl.externalinventory.ExternalLossStore.str;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 人员实仓授权下读取完整依据、候选、原件及历史永久事件。只读快照不代替写前校验。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ExternalLossQueries {
	private final ExternalLossAccess access;
	private final ExternalLossStore store;
	private final ExternalLossMandates mandates;
	private final ExternalLossEvidence evidence;
	private final ExternalLossBasis facts;
	private final ExternalLossSelection selection;

	private DetailScope detail(Identity i, String key) {
		var s = access.scope(i, key);
		access.visible(i, s);
		return s;
	}

	private DetailScope warehouse(Identity i, String key) {
		var s = new DetailScope(i.tenant().toString(), i.actor().toString(), i.factory(), key, null, null);
		access.read(i, s, true);
		return s;
	}

	/**
	 * 当前完整实物、分组复盘、货主与授权。业务阻断明确返回原因。
	 */
	public Basis basis(String key) {
		var i = access.identity();
		var s = detail(i, key);
		try {
			return facts.inspect(s, false, null).basis();
		} catch (CheckedException ex) {
			return new Basis(false, ex.getMessage(), null, null, null, null, null, null, null, null, null, null, null,
					List.of());
		}
	}

	/**
	 * 完整范围核验成功后才做数值ID游标截页，默认50行。
	 */
	public Candidates candidates(String key, String after) {
		var i = access.identity();
		var s = detail(i, key);
		long cursor = after == null ? 0 : id(after);
		var all = facts.inspect(s, false, null).candidates().stream().filter(c -> id(c.stockId()) > cursor).toList();
		return new Candidates(all.stream().limit(50).toList(), all.size() > 50 ? all.get(49).stockId() : null);
	}

	/**
	 * 同仓货主搜索仅转发当前工厂的最小主体投影。
	 */
	public List<Owner> owners(String warehouse, String type, String keyword) {
		var i = access.identity();
		return mandates.owners(warehouse(i, warehouse), type, keyword);
	}

	/**
	 * 授权历史取一页，保留原提议、审核与撤销分别发生的事实。
	 */
	public MandatePage mandates(String warehouse, String before) {
		var i = access.identity();
		return mandates.history(warehouse(i, warehouse), before);
	}

	/**
	 * 当前人员可选择或按已绑定实例查看的原件元数据。
	 */
	public List<Evidence> evidence(String key) {
		var i = access.identity();
		return evidence.list(i, detail(i, key));
	}

	/**
	 * 授权后下载重新核对摘要和媒体类型的原始字节。
	 */
	public ResponseEntity<byte[]> file(String key) {
		return evidence.download(key);
	}

	/**
	 * 历史页仅使用当时真实迁移与POST源见证，不用今日主数据、审批状态或库存。
	 */
	public History history(String key, String before) {
		var i = access.identity();
		var s = detail(i, key);
		Long cursor = before == null ? null : id(before);
		var rows = store.jdbc.queryForList("""
				select e.id,e.request_id,e.source_id,e.action,e.result_status,e.actor_id,e.actor_code,e.action_time,
				e.after_projection->>'reason' as reason,case when e.action='REVOKE' then e.after_projection->>'revoke_reason'
				when e.action in ('APPROVE','REJECT') then e.after_projection->>'review_comment' end as comment,
				(e.after_projection->>'selected_detail_json')::jsonb#>>'{confirmation,id}' as evidence_id,
				(e.after_projection->>'selected_detail_json')::jsonb#>>'{confirmation,hash}' as sha256
				from mom_wms.wms_external_loss_effect e join mom_wms.wms_external_loss_request r on r.tenant_id=e.tenant_id
				and r.effect_id=e.id and r.status='APPLIED' and r.request_id=e.request_id
				where e.tenant_id=? and e.factory_code=? and e.warehouse_code=? and e.plan_id=? and e.plan_detail_id=? and e.domain='OA1'
				and (?::bigint is null or e.id<?::bigint) order by e.id desc limit 51
				""", i.tenant(), i.factory(), s.warehouseCode(), id(s.planId()), id(key), cursor, cursor);
		var result = new ArrayList<HistoryRow>();
		for (var r : rows.stream().limit(50).toList()) {
			var lines = "POST".equals(str(r, "action")) ? posted(i, str(r, "request_id")) : List.<PostedLine>of();
			result.add(new HistoryRow(str(r, "id"), str(r, "request_id"), str(r, "action"), str(r, "source_id"),
					str(r, "result_status"), str(r, "actor_id"),
					str(r, "actor_code"), str(r, "action_time"), str(r, "reason"), str(r, "comment"),
					str(r, "evidence_id"), str(r, "sha256"), lines));
		}
		return new History(result, rows.size() > 50 ? result.get(49).effectId() : null);
	}

	private List<PostedLine> posted(Identity i, String request) {
		var rows = store.jdbc.queryForList("""
				select after_projection->>'bill_id' as bill,after_projection->>'bill_no' as no,
				after_projection->>'bill_detail_id' as line,source_id,after_projection->>'source_stock_id' as stock,
				after_projection->>'mat_num' as amount,after_projection->>'mat_unit' as unit,after_projection->>'operation_request_id' as op
				from mom_wms.wms_external_loss_witness where tenant_id=? and request_id=? and source_table='wms_inout_flow'
				and operation='INSERT' order by (after_projection->>'source_stock_id')::bigint
				""", i.tenant(), request);
		require(!rows.isEmpty(), "历史盘亏缺少实际出库流水见证");
		return rows.stream().map(r -> new PostedLine(str(r, "bill"), str(r, "no"), str(r, "line"), str(r, "source_id"),
				str(r, "stock"),
				ExternalLossBasis.decimal(new BigDecimal(str(r, "amount"))), str(r, "unit"), str(r, "op"))).toList();
	}

	/**
	 * 按原审批主键和实际可见明细唯一读取已受理的申请事件，返回冻结实物及原始来源投影。
	 * 文件、当前库存及今日主体状态不参与历史读取；跨范围、原件缺失或不唯一时拒绝。
	 */
	public FrozenAllocations allocations(String planDetailId, String approvalId) {
		long detailId = id(planDetailId);
		long approval = id(approvalId);
		var i = access.identity();
		var scope = detail(i, planDetailId);
		var row = store.one("""
				select e.after_projection->>'selected_detail_json' as frozen,d.mat_code,d.mat_unit,d.mat_batch,d.site_code
				from mom_wms.wms_external_loss_effect e
				join mom_wms.wms_external_loss_request r on r.effect_id=e.id and r.tenant_id=e.tenant_id
				   and r.request_id=e.request_id and r.status='APPLIED' and r.domain=e.domain and r.action=e.action
				   and (r.factory_code,r.warehouse_code,r.plan_id,r.plan_detail_id)=(e.factory_code,e.warehouse_code,e.plan_id,e.plan_detail_id)
				join mom_wms.wms_inside_inventory_plan_detail d on (d.id,d.tenant_id,d.factory_code,d.warehouse_code,d.plan_id)
				    =(e.plan_detail_id,e.tenant_id,e.factory_code,e.warehouse_code,e.plan_id)
				where e.tenant_id=? and e.factory_code=? and e.warehouse_code=? and e.plan_id=? and e.plan_detail_id=?
				  and e.source_id=? and e.domain='OA1' and e.action='SUBMIT' and e.result_status='PENDING'
				  and e.after_projection->>'id'=e.source_id::text and e.after_projection->>'request_id'=e.request_id
				""", i.tenant(), i.factory(), scope.warehouseCode(), id(scope.planId()), detailId, approval);
		return selection.allocations(scope, approvalId, str(row, "frozen"), str(row, "mat_code"), str(row, "mat_unit"),
				str(row, "mat_batch"), str(row, "site_code"));
	}
}
