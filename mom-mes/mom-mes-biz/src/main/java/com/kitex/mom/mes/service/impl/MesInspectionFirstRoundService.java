package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesManufacturingPieceService.*;
import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesInspectionFirstRoundDTO;
import com.kitex.mom.mes.dto.MesInspectionFirstRoundDTO.*;
import com.kitex.mom.mes.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Remote reservations survive a QMS rollback and belong forever to the same original sample
 * request.
 */
@Service
@RequiredArgsConstructor
public class MesInspectionFirstRoundService {
	private final MesManufacturingPieceMapper rows;
	private final MesInspectionPieceEntryService entries;
	private final MesQmsInspectionCommandMapper commands;

	/**
	 * 核对实际操作者、制造出生、原入站和已确认QMS任务，永久占有同件同节点的原采集归属；同号同事实返回原件，同号异事实拒绝。
	 */
	@Transactional(rollbackFor = Exception.class)
	public View claim(Claim q) {
		MesQualityGateSourceEvidence.scope();
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		require(
				q != null
						&& q.source() != null
						&& q.source().identifier() != null
						&& q.source().identifier().length() <= 100
						&& q.qmsTaskId() != null
						&& q.qmsTaskId().matches("[1-9][0-9]{0,18}")
						&& q.qmsRequestId() != null
						&& q.qmsRequestId().matches("[A-Za-z0-9_-]{16,80}")
						&& q.qmsRequestFingerprint() != null
						&& q.qmsRequestFingerprint().matches("[0-9a-f]{64}")
						&& Objects.equals(
						q.qmsActorId(), SecurityUtils.getUser().getId().toString()),
				"首次检验归属缺少原请求、指纹或实际操作者");
		if (!ready(rows)) {
			return view("SCHEMA_UNAVAILABLE", null, null);
		}
		var row = rows.piece(tenant, factory, q.source().identifier());
		if (row == null) {
			return view("NOT_MANUFACTURING_ID", null, null);
		}
		var born = read(row);
		var birth = born.birth();
		var replay =
				rows.replay(tenant, factory, birth.identifier(), q.qmsTaskId(), q.qmsRequestId());
		require(replay.size() <= 1, "原采集请求存在重复首次检验归属");
		if (!replay.isEmpty()) {
			var prior = replay.get(0);
			var saved = restore(prior, born, tenant, factory);
			require(saved.claim().equals(q), "原采集请求的首次检验内容或操作者已变化");
			return view("ASSIGNED", saved, prior.getFingerprint());
		}
		var entry = entries.query(q.source());
		var context = entry.productionBasis();
		require(
				"MATCHED".equals(entry.availability())
						&& context != null
						&& "FROZEN".equals(context.availability())
						&& birth.tenantId().equals(context.tenantId())
						&& birth.factoryCode().equals(context.factoryCode())
						&& birth.identifier().equals(entry.identifier())
						&& birth.orderId().equals(context.orderId())
						&& birth.orderCode().equals(context.orderCode())
						&& birth.engineeringHash().equals(context.engineeringHash())
						&& birth.engineeringReleaseId().equals(context.engineeringReleaseId())
						&& birth.quantityUnit().equals(context.quantityUnit()),
				"首次检验与原制造身份、工程、单位或实际入站不一致");
		var source = commands.original(tenant, q.source().requestId());
		require(
				source != null
						&& Objects.equals(source.getTenantId(), tenant)
						&& factory.equals(source.getFactoryCode())
						&& "QMS_CONFIRMED".equals(source.getStatus())
						&& Objects.equals(String.valueOf(source.getQmsTaskId()), q.qmsTaskId()),
				"首次检验必须使用已确认的原QMS任务");
		rows.lock(
				"INSPECTION_FIRST_ROUND:"
						+ tenant
						+ ":"
						+ factory
						+ ":"
						+ birth.identifier()
						+ ":"
						+ context.routeNodeCode());
		var prior = rows.assignment(tenant, factory, birth.identifier(), context.routeNodeCode());
		if (prior != null) {
			var saved = restore(prior, born, tenant, factory);
			return saved.claim().equals(q)
					? view("ASSIGNED", saved, prior.getFingerprint())
					: view("OTHER_INSPECTION", null, null);
		}
		var assignment = new Assignment(MesInspectionFirstRoundDTO.SCHEMA, q, born, entry);
		var own = new MesManufacturingPieceMapper.Row();
		own.setTenantId(tenant);
		own.setFactoryCode(factory);
		own.setActorId(SecurityUtils.getUser().getId());
		own.setIdentifier(birth.identifier());
		own.setNodeCode(context.routeNodeCode());
		own.setQmsTaskId(q.qmsTaskId());
		own.setQmsRequestId(q.qmsRequestId());
		own.setQmsFingerprint(q.qmsRequestFingerprint());
		own.setPayload(write(assignment));
		own.setFingerprint(sha(own.getPayload()));
		require(rows.insertAssignment(own) == 1, "首次检验原归属保存失败，未确认资格");
		return view("ASSIGNED", assignment, own.getFingerprint());
	}

	/**
	 * 恢复永久原归属并校验规范序列化、指纹、组织及原索引；损坏或绑定不一致时拒绝。
	 */
	private Assignment restore(
			MesManufacturingPieceMapper.Row row,
			com.kitex.mom.mes.dto.MesManufacturingPieceDTO.Receipt born,
			Long tenant,
			String factory) {
		require(
				row.getPayload() != null
						&& row.getPayload().length() <= 524288
						&& Objects.equals(sha(row.getPayload()), row.getFingerprint()),
				"首次检验原件指纹损坏");
		var a = read(row.getPayload(), Assignment.class);
		require(
				write(a).equals(row.getPayload())
						&& MesInspectionFirstRoundDTO.SCHEMA.equals(a.schemaVersion())
						&& Objects.equals(row.getTenantId(), tenant)
						&& Objects.equals(row.getFactoryCode(), factory)
						&& Objects.equals(row.getIdentifier(), born.birth().identifier())
						&& Objects.equals(a.manufacturingPiece(), born)
						&& Objects.equals(a.claim().qmsTaskId(), row.getQmsTaskId())
						&& Objects.equals(a.claim().qmsRequestId(), row.getQmsRequestId())
						&& Objects.equals(
						a.claim().qmsRequestFingerprint(), row.getQmsFingerprint())
						&& Objects.equals(a.claim().qmsActorId(), String.valueOf(row.getActorId()))
						&& Objects.equals(
						a.entry().productionBasis().routeNodeCode(), row.getNodeCode())
						&& Objects.equals(a.entry().tenantId(), tenant.toString())
						&& Objects.equals(a.entry().factoryCode(), factory),
				"首次检验原文与实际组织或保留索引不一致");
		return a;
	}

	/**
	 * 按已核实原状态构造只读投影，不在此授予原首轮或改写样本。
	 */
	private View view(String state, Assignment assignment, String hash) {
		return new View(MesInspectionFirstRoundDTO.SCHEMA, state, assignment, hash);
	}
}
