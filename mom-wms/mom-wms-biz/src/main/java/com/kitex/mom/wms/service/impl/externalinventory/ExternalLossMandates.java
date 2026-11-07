package com.kitex.mom.wms.service.impl.externalinventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.*;
import com.kitex.mom.mdm.feign.RemoteMdmStockOwnerService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryAccess.Identity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;
import static com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryCodec.id;
import static com.kitex.mom.wms.service.impl.externalinventory.ExternalLossStore.str;

/**
 * 本仓企业授权的版本、人岗分离与授权期间排他；MDM身份按实际人员令牌读取。
 */
@Service
@RequiredArgsConstructor
public class ExternalLossMandates {
	private final WmsWarehouseBusinessGuard warehouseGuard;
	private final ExternalLossStore store;
	private final RemoteMdmStockOwnerService mdm;
	private final ObjectMapper json;
	private static final String SELECT = """
			select m.*,(m.status='ACTIVE' and current_date between m.valid_from and m.valid_until) as current
			from mom_wms.wms_external_loss_mandate m where m.tenant_id=? and m.factory_code=? and m.warehouse_code=?
			""";

	/**
	 * 当前人员令牌下精确核验同厂同码唯一身份及状态，传预期ID时拒绝改映射。
	 */
	Owner resolve(DetailScope s, String type, String code, String expectedId) {
		var q = new MdmStockOwnerLookupDTO();
		q.setOwnerType(type);
		q.setOwnerCode(code);
		q.setExpectedOwnerId(expectedId);
		var response = mdm.resolve(q);
		require(response != null && response.isOk() && response.getData() != null, "货主权威身份查询失败");
		var owner = owner(response.getData(), s, type);
		require(code.equals(owner.code()) && (expectedId == null || expectedId.equals(owner.id()))
				&& Boolean.TRUE.equals(owner.eligible()), "货主身份、状态或供应商审核状态已变化");
		return owner;
	}

	private Owner owner(MdmStockOwnerIdentityDTO o, DetailScope s, String type) {
		require(o != null && s.tenantId().equals(o.getTenantId()) && s.factoryCode().equals(o.getFactoryCode()) &&
						type.equals(o.getOwnerType())
						&& o.getName() != null && !o.getName().isBlank() && o.getCode() != null && !o.getCode().isBlank(),
				"MDM货主投影组织或字段不符");
		id(o.getId());
		return new Owner(o.getId(), o.getTenantId(), o.getFactoryCode(), type, o.getCode(), o.getName(), o.getEnabled(),
				o.getSupplierCheckStatus(), o.getEligible());
	}

	/**
	 * 按已核实的仓范围检索同厂候选，最多20个最小投影。
	 */
	List<Owner> owners(DetailScope s, String type, String keyword) {
		require(Set.of("CUSTOMER_SUPPLIED", "CONSIGNED").contains(type) && keyword != null && !keyword.isBlank() &&
						keyword.trim().length() <= 100,
				"请提供实际货主类型和关键词");
		var q = new MdmStockOwnerSearchDTO();
		q.setOwnerType(type);
		q.setKeyword(keyword.trim());
		var response = mdm.search(q);
		require(response != null && response.isOk() && response.getData() != null && response.getData().size() <= 20,
				"货主检索未成功");
		return response.getData().stream().map(o -> owner(o, s, type)).toList();
	}

	private void lockRange(DetailScope s, String type, String ownerId) {
		store.jdbc.queryForList("""
				select pg_advisory_xact_lock(hashtextextended(jsonb_build_array(?::bigint,?::text,?::text,?::text,?::bigint,'LOSS')::text,0))
				""", Long.valueOf(s.tenantId()), s.factoryCode(), s.warehouseCode(), type, Long.valueOf(ownerId));
	}

	/**
	 * 先锁授权范围，决定时再锁原行；异人、旧版本和有效日期均以当次数据库事实裁定。
	 */
	long apply(ExternalLossCodec.Parsed p, Identity i) {
		var command = (MandateCommand) p.typed();
		var s = p.scope();
		if (command.proposal() != null) {
			var v = command.proposal();
			lockRange(s, v.ownerType(), v.mdmOwnerId());
			warehouseGuard.requireEnabledWarehouse(s.warehouseCode());
			var o = resolve(s, v.ownerType(), v.ownerCode(), v.mdmOwnerId());
			require(store.jdbc.queryForList(SELECT + """
									and owner_type=? and mdm_owner_id=? and direction='LOSS' and status in ('ACTIVE','PENDING')
									and valid_until>=current_date and valid_from<=?::date and valid_until>=?::date
									""", i.tenant(), i.factory(), s.warehouseCode(), v.ownerType(), Long.valueOf(v.mdmOwnerId()),
							v.validUntil(), v.validFrom()).isEmpty(),
					"该主体已有重叠待审或生效授权");
			try {
				return store.jdbc.queryForObject("""
								insert into mom_wms.wms_external_loss_mandate
								(tenant_id,factory_code,warehouse_code,request_id,owner_type,mdm_owner_id,owner_code,owner_name,identity_json,identity_read_at,
								direction,valid_from,valid_until,authority_no,status,version,proposer_id,proposer_code,proposed_at)
								select ?,?,?,?,?,?,?,?,?::jsonb,clock_timestamp(),'LOSS',?::date,?::date,?,'PENDING',1,?,?,clock_timestamp()
								where ?::date>=current_date returning id
								""", Long.class, i.tenant(), i.factory(), s.warehouseCode(), p.envelope().requestId(),
						v.ownerType(), Long.valueOf(o.id()), o.code(), o.name(),
						json.writeValueAsString(o), v.validFrom(), v.validUntil(), v.authorityNo(), i.actor(),
						i.actorCode(), v.validUntil());
			} catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
				throw new CheckedException("货主快照保存失败");
			}
		}
		var v = command.decision();
		long key = id(v.mandateId());
		var before = load(s, key, false);
		lockRange(s, before.ownerType(), before.mdmOwnerId());
		var m = load(s, key, true);
		require(m.version().equals(v.expectedVersion()) && !i.actor().toString().equals(m.proposerId())
				&& !i.actorCode().equals(m.proposerCode()), "授权版本已变化或审核人为提议人");
		boolean revoke = "REVOKE".equals(p.action());
		String previous = revoke ? "ACTIVE" : "PENDING";
		require(previous.equals(m.status()), "企业授权状态不支持该动作");
		boolean approve = "APPROVE".equals(p.action());
		if (approve) {
			warehouseGuard.requireEnabledWarehouse(s.warehouseCode());
			resolve(s, m.ownerType(), m.ownerCode(), m.mdmOwnerId());
		}
		String sets = revoke ? "revoker_id=?,revoker_code=?,revoked_at=clock_timestamp(),revoke_comment=?" :
				"reviewer_id=?,reviewer_code=?,reviewed_at=clock_timestamp(),review_comment=?";
		String result = revoke ? "REVOKED" : approve ? "ACTIVE" : "REJECTED";
		require(store.jdbc.update(
				"update mom_wms.wms_external_loss_mandate set " + sets + ",status=?,version=version+1 " +
						"where tenant_id=? and factory_code=? and warehouse_code=? and id=? and status=? and version=?" +
						(approve ? " and valid_until>=current_date" : ""), i.actor(), i.actorCode(), v.comment(),
				result, i.tenant(), i.factory(), s.warehouseCode(), key,
				previous, Integer.valueOf(v.expectedVersion())) == 1, "授权版本、有效期或状态已变化");
		return key;
	}

	/**
	 * 精确取得该仓货主当前唯一生效授权，期限使用数据库当日。
	 */
	Mandate active(DetailScope s, String type, String ownerId, boolean lock) {
		return row(store.one(SELECT + """
						and owner_type=? and mdm_owner_id=? and direction='LOSS' and status='ACTIVE'
						and current_date between valid_from and valid_until
						""" + (lock ? " for update nowait" : ""), Long.valueOf(s.tenantId()), s.factoryCode(),
				s.warehouseCode(), type, id(ownerId)));
	}

	/**
	 * 按实际厂仓读取指定授权，写事务可加NOWAIT锁。
	 */
	Mandate load(DetailScope s, long key, boolean lock) {
		return row(store.one(SELECT + " and id=?" + (lock ? " for update nowait" : ""), Long.valueOf(s.tenantId()),
				s.factoryCode(), s.warehouseCode(), key));
	}

	/**
	 * 按本仓授权主键降序读取50条，明确下页游标。
	 */
	MandatePage history(DetailScope s, String before) {
		var rows = store.jdbc.queryForList(
				SELECT + " and (?::bigint is null or id<?::bigint) order by id desc limit 51",
				Long.valueOf(s.tenantId()),
				s.factoryCode(), s.warehouseCode(), before == null ? null : id(before), before);
		var page = rows.stream().limit(50).map(this::row).toList();
		return new MandatePage(page, rows.size() > 50 ? page.get(49).id() : null);
	}

	private Mandate row(Map<String, Object> r) {
		return new Mandate(str(r, "id"), str(r, "version"), str(r, "status"), str(r, "owner_type"),
				str(r, "mdm_owner_id"), str(r, "owner_code"),
				str(r, "owner_name"), str(r, "direction"), str(r, "valid_from"), str(r, "valid_until"),
				str(r, "authority_no"), str(r, "proposer_id"),
				str(r, "proposer_code"), str(r, "proposed_at"), str(r, "reviewer_id"), str(r, "reviewer_code"),
				str(r, "reviewed_at"), str(r, "review_comment"),
				str(r, "revoker_id"), str(r, "revoker_code"), str(r, "revoked_at"), str(r, "revoke_comment"),
				Boolean.TRUE.equals(r.get("current")));
	}
}
