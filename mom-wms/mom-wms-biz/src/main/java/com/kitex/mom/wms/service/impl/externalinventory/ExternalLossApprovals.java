package com.kitex.mom.wms.service.impl.externalinventory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.WmsInventoryAdjustmentApprovalMapper;
import com.kitex.mom.wms.service.impl.*;
import com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryAccess.Identity;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;
import static com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryCodec.id;
import static com.kitex.mom.wms.service.impl.externalinventory.ExternalLossStore.str;

/**
 * 复用原审批行、原盘点差异政策和原出库服务实现外部五次迁移。
 */
@Service
@RequiredArgsConstructor
public class ExternalLossApprovals {
	private final WmsInventoryAdjustmentApprovalMapper approvals;
	private final WmsInventoryAdjustmentService adjustments;
	private final WmsInventoryVarianceGate policies;
	private final WmsInventoryPlanAccess plans;
	private final ExternalLossStore store;
	private final ExternalLossBasis basis;
	private final ExternalLossEvidence evidence;
	private final ExternalLossSelection selections;
	private final ExternalLossReceipt receipts;

	/**
	 * 调用方已取得原号与实仓互斥锁；先锁真实计划明细与最新审批，返回本次迁移的原审批主键。
	 */
	long apply(ExternalLossCodec.Parsed p, Identity i) {
		var s = p.scope();
		var row = plans.detail(id(s.planDetailId()));
		require(s.warehouseCode().equals(row.getWarehouseCode()) && s.planId().equals(row.getPlanId().toString()) &&
				"0".equals(row.getPlStatus()), "原明细已办理或计划范围发生变化");
		var latest = approvals.selectOne(Wrappers.<WmsInventoryAdjustmentApprovalEntity>lambdaQuery()
				.eq(WmsInventoryAdjustmentApprovalEntity::getTenantId, i.tenant())
				.eq(WmsInventoryAdjustmentApprovalEntity::getFactoryCode, i.factory())
				.eq(WmsInventoryAdjustmentApprovalEntity::getPlanDetailId, row.getId())
				.orderByDesc(WmsInventoryAdjustmentApprovalEntity::getId)
				.last("limit 1 for update nowait"));
		var c = (ApprovalCommand) p.typed();
		if (c.submit() != null) {
			require(latest == null || Set.of("REJECTED", "REVOKED").contains(latest.getStatus()),
					"该明细已有活动申请或已办理");
			return submit(p, i, c.submit());
		}
		String key = c.post() != null ? c.post().approvalId() : c.review().approvalId();
		boolean posted = "POST".equals(p.action());
		boolean revoked = "REVOKE".equals(p.action());
		require(latest != null && key.equals(latest.getId().toString()) && latest.getRequestId() != null &&
				latest.getRequestId().startsWith("OA1_")
				&& (posted || revoked ? "APPROVED" : "PENDING").equals(latest.getStatus()) &&
				s.planId().equals(latest.getPlanId().toString()) &&
				s.warehouseCode().equals(latest.getWarehouseCode()), "审批对象、状态或外部原号不符");
		var frozen = selections.read(latest.getSelectedDetailJson());
		if (!posted) {
			var orig = store.one("""
							select actor_id from mom_wms.wms_external_loss_effect where tenant_id=? and factory_code=? and warehouse_code=?
							and plan_detail_id=? and domain='OA1' and action='SUBMIT' and request_id=? and source_id=?
							""", i.tenant(), i.factory(), s.warehouseCode(), row.getId(), latest.getRequestId(),
					latest.getId());
			require(!s.actorId().equals(str(orig, "actor_id")) && !i.actorCode().equals(latest.getApplicantCode()),
					"申请人与审核或撤销人须为不同人员");
		}
		if (posted || "APPROVE".equals(p.action())) {
			String eid = frozen.path("confirmation").path("id").asText();
			var facts = basis.inspect(s, true, eid);
			var file = evidence.load(s, id(eid), true);
			require(key.equals(file.approvalId()), "证件未永久绑定当前审批实例");
			selections.verify(s, facts, file, frozen);
			require(BigDecimal.valueOf(latest.getExpectedCurrentNumber())
					.compareTo(BigDecimal.valueOf(row.getCurrentNumber())) == 0 &&
					BigDecimal.valueOf(latest.getExpectedInventoryNum())
							.compareTo(BigDecimal.valueOf(row.getInventoryNum())) == 0, "原差额已变化");
			if (posted) {
				post(p, i, latest, facts, frozen);
				return latest.getId();
			}
			var r = c.review();
			require(Boolean.TRUE.equals(r.verified()) && file.id().equals(r.evidenceId()) &&
							file.registration().sha256().equals(r.evidenceSha256()),
					"须明确核验本审批冻结的原件及其摘要");
		}
		String expected = revoked ? "APPROVED" : "PENDING";
		String status = revoked ? "REVOKED" : "REJECT".equals(p.action()) ? "REJECTED" : "APPROVED";
		String set = revoked ? "revoked_by=?,revoked_time=clock_timestamp(),revoke_reason=?" : "reviewer_code=?,reviewed_time=clock_timestamp(),review_comment=?";
		require(store.jdbc.update("update mom_wms.wms_inventory_adjustment_approval set " + set +
								",status=?,update_by=?,update_time=clock_timestamp() " +
								"where tenant_id=? and factory_code=? and warehouse_code=? and plan_id=? and plan_detail_id=? and id=? and status=? and del_flag='0'",
						i.actorCode(), c.review().comment(), status, i.actorCode(), i.tenant(), i.factory(), s.warehouseCode(),
						id(s.planId()), row.getId(), latest.getId(), expected) == 1,
				"盘亏审批迁移失败，业务已回滚");
		return latest.getId();
	}

	/**
	 * 只用本人未绑定证件及当前精确依据冻结原审批；单次绑定与申请同事务。
	 */
	private long submit(ExternalLossCodec.Parsed p, Identity i, Submit v) {
		var s = p.scope();
		var facts = basis.inspect(s, true, v.evidenceId());
		var b = facts.basis();
		var e = evidence.load(s, id(v.evidenceId()), true);
		require(e.selectable() && i.actor().toString().equals(e.registeredBy()) &&
						v.evidenceSha256().equals(e.registration().sha256()) &&
						v.recountId().equals(b.recountId()) && v.basisVersion().equals(b.basisVersion()) &&
						v.groupKey().equals(b.groupKey()) &&
						v.expectedCurrentNumber().equals(b.expectedCurrentNumber()) &&
						v.expectedInventoryNum().equals(b.expectedInventoryNum()) &&
						v.mandateId().equals(b.mandate().id()) && v.mandateVersion().equals(b.mandate().version()),
				"原号依据已失效或证件不属于当前本人");
		var policy = policies.externalPolicy(facts.detail());
		var frozen = selections.build(s, facts, e, v.lossStocks());
		var a = new WmsInventoryAdjustmentApprovalEntity();
		a.setTenantId(i.tenant());
		a.setFactoryCode(i.factory());
		a.setWarehouseCode(s.warehouseCode());
		a.setPlanId(id(s.planId()));
		a.setPlanDetailId(id(s.planDetailId()));
		a.setRequestId(p.envelope().requestId());
		a.setStatus("PENDING");
		a.setExpectedCurrentNumber(facts.detail().getCurrentNumber());
		a.setExpectedInventoryNum(facts.detail().getInventoryNum());
		a.setSelectedDetailJson(frozen.toString());
		a.setVariancePolicyJson(policy);
		a.setApplicantCode(i.actorCode());
		a.setReason(v.reason());
		a.setSubmittedTime(store.jdbc.queryForObject("select clock_timestamp()::timestamp", LocalDateTime.class));
		require(approvals.insert(a) == 1 && a.getId() != null, "盘亏申请新增失败");
		require(store.jdbc.update(
				"insert into mom_wms.wms_external_loss_binding(evidence_id,approval_id,tenant_id,request_id) values(?,?,?,?)",
				id(e.id()), a.getId(), i.tenant(), p.envelope().requestId()) == 1, "证件已被绑定或保存失败");
		return a.getId();
	}

	/**
	 * 精确出库必须走原单据服务，实际收发证据闭合后才可分别CAS完成明细及审批。
	 */
	private void post(ExternalLossCodec.Parsed p, Identity i, WmsInventoryAdjustmentApprovalEntity a,
	                  ExternalLossBasis.Facts f, ObjectNode frozen) {
		var row = f.detail();
		receipts.noBill(row);
		var bill = new WmsOutBillEntity();
		bill.setTenantId(i.tenant());
		bill.setFactoryCode(i.factory());
		bill.setWarehouseCode(row.getWarehouseCode());
		bill.setWarehouseName(row.getWarehouseName());
		bill.setBillNo("IC-" + row.getId());
		bill.setBillType("other_inventory");
		bill.setDocumentId(row.getId());
		bill.setDocumentTable(WmsInventoryAdjustmentContext.DOCUMENT);
		var lines = new LinkedHashMap<Long, ExternalLossContext.Line>();
		for (var piece : frozen.path("lossStocks")) {
			long sid = id(piece.path("stockId").textValue());
			var stock = f.stocks().stream().filter(st -> st.getId() == sid).findFirst().orElseThrow();
			var source = f.basis().sources().stream().filter(src -> src.stockId().equals(Long.toString(sid)))
					.findFirst().orElseThrow();
			var quantity = new BigDecimal(piece.path("quantity").textValue());
			var d = new WmsOutBillDetailEntity();
			BeanUtils.copyProperties(stock, d, "id", "createTime", "updateTime", "createBy", "updateBy", "delFlag");
			d.setMatNum(quantity.doubleValue());
			d.setOperateNum(quantity.doubleValue());
			d.setOperateUnit(stock.getMatUnit());
			d.setSourceStockId(sid);
			lines.put(sid, new ExternalLossContext.Line(d, source, ExternalLossProvenance.physical(stock), quantity));
		}
		bill.setDetails(lines.values().stream().map(ExternalLossContext.Line::detail).toList());
		ExternalLossContext.post(store, p, a.getId(), bill, lines, () -> {
			adjustments.postExternal(bill);
			return true;
		});
		receipts.check(row, frozen, true);
		require(store.jdbc.update("""
						update mom_wms.wms_inside_inventory_plan_detail set pl_status='1',update_by=?,update_time=clock_timestamp()
						where id=? and tenant_id=? and factory_code=? and warehouse_code=? and plan_id=? and pl_status='0' and status='1' and del_flag='0'
						""", i.actorCode(), row.getId(), i.tenant(), i.factory(), row.getWarehouseCode(), row.getPlanId()) == 1,
				"实际盘点明细完成失败，本次出库回滚");
		require(store.jdbc.update("""
						update mom_wms.wms_inventory_adjustment_approval set status='POSTED',posted_time=clock_timestamp(),update_by=?,update_time=clock_timestamp()
						where id=? and tenant_id=? and factory_code=? and warehouse_code=? and plan_detail_id=? and status='APPROVED' and del_flag='0'
						""", i.actorCode(), a.getId(), i.tenant(), i.factory(), row.getWarehouseCode(), row.getId()) == 1,
				"实际盘亏审批入账失败，本次出库回滚");
	}
}
