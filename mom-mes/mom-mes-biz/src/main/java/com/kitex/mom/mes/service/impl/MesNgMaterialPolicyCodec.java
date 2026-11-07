package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.mapper.MesNgMaterialPolicyMapper.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 固定字段协议；每次读回比较typed全集，任何缺行或来源漂移均拒绝。
 */
@Component
public class MesNgMaterialPolicyCodec {
	public static final String VERSION = "MES_NG_MATERIAL_POLICY_V1";
	public static final String RESTORE = "MES_NG_MATERIAL_POLICY_RESTORE_V1";
	private final MesPhysicalTransformCodec codec = new MesPhysicalTransformCodec();

	/**
	 * 从Header和完整Line集合生成固定版本证据并写入原文及SHA；身份、来源、精确当量或方向不完整即拒绝。
	 */
	public void freeze(Header h, List<Line> lines) {
		h.setEvidenceVersion(VERSION);
		h.setEvidenceSnapshot(document(h, lines));
		h.setEvidenceFingerprint(codec.fingerprint(VERSION, h.getEvidenceSnapshot()));
	}

	/**
	 * 验证原账版本、SHA及typed完整份额双向一致；缺行、增行、数量或来源漂移均拒绝。
	 */
	public void verify(Header h, List<Line> lines) {
		require(h != null && VERSION.equals(h.getEvidenceVersion())
				&& Objects.equals(h.getEvidenceFingerprint(), codec.fingerprint(VERSION, h.getEvidenceSnapshot()))
				&& Objects.equals(codec.objectDocument(h.getEvidenceSnapshot(), 16 * 1024 * 1024),
				codec.objectDocument(document(h, lines), 16 * 1024 * 1024)), "NG逐料原账SHA或完整typed份额不一致");
	}

	/**
	 * 校验真实原身份和全体正量份额后生成规范化证据；数量按原单位精确计算，不截断全集。
	 */
	private String document(Header h, List<Line> lines) {
		require(h != null && lines != null && lines.size() <= 5000, "NG原账或份额全集不完整");
		for (Long id : Arrays.asList(h.getId(), h.getTenantId(), h.getSourceUnqualifiedReportId(),
				h.getSourceBirthId(), h.getActorId(), h.getGateId())) {
			require(id != null && id > 0, "NG原账缺少真实主键");
		}
		for (String text : Arrays.asList(h.getFactoryCode(), h.getTaskCode(), h.getProduceTaskCode(),
				h.getArrangeCode(), h.getRouteNodeCode(), h.getRequestId(), h.getProductionUnit())) {
			codec.code(text, "NG原账身份", 100);
		}
		amount(h.getProcessedQuantity(), false);
		require(h.getSourceFingerprint() != null && h.getSourceFingerprint().matches("[a-f0-9]{64}"), "NG来源SHA缺失");
		codec.objectDocument(h.getPolicySnapshot(), 2 * 1024 * 1024);
		require(h.getRouteSnapshot() != null && h.getBomSnapshot() != null, "NG原工艺或BOM未知");
		var ids = new HashSet<Long>();
		var flows = new HashSet<Long>();
		for (var line : lines) {
			require(line != null && line.getId() != null && line.getId() > 0 && ids.add(line.getId())
					&& Objects.equals(line.getOperationId(), h.getId()) && Objects.equals(line.getTenantId(), h.getTenantId())
					&& Objects.equals(line.getFactoryCode(), h.getFactoryCode())
					&& line.getSourceId() != null && line.getSourceId() > 0, "NG原份额主键重复或跨组织");
			for (String text : Arrays.asList(line.getRequirementMatCode(), line.getRequirementUnit(), line.getMatCode(), line.getMatUnit())) {
				codec.code(text, "NG原份额物料/单位", 100);
			}
			var quantity = amount(line.getQuantity(), false);
			var equivalent = amount(line.getRequirementQuantity(), false);
			var ratio = amount(line.getSubstituteRatio(), false);
			var before = amount(line.getBeforeQuantity(), true);
			var after = amount(line.getAfterQuantity(), true);
			require(line.getSubstituteFlag() != null && quantity.compareTo(equivalent.multiply(ratio)) == 0,
					"NG原份额当量或比例不一致");
			if ("FEED".equals(line.getSourceKind())) {
				require(line.getSourceFlowId() == null && after.subtract(before).compareTo(quantity) == 0,
						"NG投料份额不是实际used_num正向增量");
			} else {
				require("STOCK".equals(line.getSourceKind()) && line.getSourceFlowId() != null && line.getSourceFlowId() > 0
								&& flows.add(line.getSourceFlowId()) && before.subtract(after).compareTo(quantity) == 0,
						"NG库存份额缺真实原OUT或原扣减量");
			}
			codec.objectDocument(line.getSourceSnapshot(), 2 * 1024 * 1024);
		}
		var result = header(h);
		result.put("allocations", lines.stream().sorted(Comparator.comparing(Line::getId)).map(this::line).toList());
		String raw = codec.snapshot(result);
		require(raw.length() <= 16 * 1024 * 1024, "NG完整原账超过16Mi字符，未允许截断");
		return raw;
	}

	/**
	 * 从原账、原完整份额及恢复全集生成独立恢复证据；必须一对一全额恢复，不能部分恢复。
	 */
	public void freezeRestore(Reversal r, List<Restore> rows, Header h, List<Line> original) {
		r.setEvidenceVersion(RESTORE);
		r.setEvidenceSnapshot(restoreDocument(r, rows, h, original));
		r.setEvidenceFingerprint(codec.fingerprint(RESTORE, r.getEvidenceSnapshot()));
	}

	/**
	 * 核对恢复原文SHA与typed全集，并逐份匹配原账；请求身份、原OUT归属或逆向数量不一致即拒绝。
	 */
	public void verifyRestore(Reversal r, List<Restore> rows, Header h, List<Line> original) {
		require(r != null && RESTORE.equals(r.getEvidenceVersion())
				&& Objects.equals(r.getEvidenceFingerprint(), codec.fingerprint(RESTORE, r.getEvidenceSnapshot()))
				&& Objects.equals(codec.objectDocument(r.getEvidenceSnapshot(), 16 * 1024 * 1024),
				codec.objectDocument(restoreDocument(r, rows, h, original), 16 * 1024 * 1024)), "NG原份额恢复SHA或typed全集不一致");
	}

	private BigDecimal amount(BigDecimal value, boolean zero) {
		require(value != null, "NG原份额数量未知");
		return codec.quantity(codec.amount(value), zero, "NG原份额数量");
	}

	private Map<String, Object> header(Header row) {
		var facts = new LinkedHashMap<String, Object>();
		facts.put("id", row.getId() == null ? null : row.getId().toString());
		facts.put("tenantId", row.getTenantId() == null ? null : row.getTenantId().toString());
		facts.put("sourceUnqualifiedReportId", row.getSourceUnqualifiedReportId() == null ? null : row.getSourceUnqualifiedReportId().toString());
		facts.put("sourceBirthId", row.getSourceBirthId() == null ? null : row.getSourceBirthId().toString());
		facts.put("actorId", row.getActorId() == null ? null : row.getActorId().toString());
		facts.put("gateId", row.getGateId() == null ? null : row.getGateId().toString());
		facts.put("factoryCode", row.getFactoryCode());
		facts.put("requestId", row.getRequestId());
		facts.put("taskCode", row.getTaskCode());
		facts.put("produceTaskCode", row.getProduceTaskCode());
		facts.put("arrangeCode", row.getArrangeCode());
		facts.put("routeNodeCode", row.getRouteNodeCode());
		facts.put("productionUnit", row.getProductionUnit());
		facts.put("sourceFingerprint", row.getSourceFingerprint());
		facts.put("policySnapshot", row.getPolicySnapshot());
		facts.put("routeSnapshot", row.getRouteSnapshot());
		facts.put("bomSnapshot", row.getBomSnapshot());
		facts.put("evidenceVersion", row.getEvidenceVersion());
		facts.put("processedQuantity", row.getProcessedQuantity() == null ? null : codec.amount(row.getProcessedQuantity()));
		return facts;
	}

	private Map<String, Object> line(Line row) {
		var facts = new LinkedHashMap<String, Object>();
		facts.put("id", row.getId() == null ? null : row.getId().toString());
		facts.put("operationId", row.getOperationId() == null ? null : row.getOperationId().toString());
		facts.put("tenantId", row.getTenantId() == null ? null : row.getTenantId().toString());
		facts.put("sourceId", row.getSourceId() == null ? null : row.getSourceId().toString());
		facts.put("sourceFlowId", row.getSourceFlowId() == null ? null : row.getSourceFlowId().toString());
		facts.put("factoryCode", row.getFactoryCode());
		facts.put("sourceKind", row.getSourceKind());
		facts.put("requirementMatCode", row.getRequirementMatCode());
		facts.put("requirementUnit", row.getRequirementUnit());
		facts.put("matCode", row.getMatCode());
		facts.put("matUnit", row.getMatUnit());
		facts.put("sourceSnapshot", row.getSourceSnapshot());
		facts.put("quantity", row.getQuantity() == null ? null : codec.amount(row.getQuantity()));
		facts.put("requirementQuantity", row.getRequirementQuantity() == null ? null : codec.amount(row.getRequirementQuantity()));
		facts.put("substituteRatio", row.getSubstituteRatio() == null ? null : codec.amount(row.getSubstituteRatio()));
		facts.put("beforeQuantity", row.getBeforeQuantity() == null ? null : codec.amount(row.getBeforeQuantity()));
		facts.put("afterQuantity", row.getAfterQuantity() == null ? null : codec.amount(row.getAfterQuantity()));
		facts.put("substituteFlag", row.getSubstituteFlag());
		return facts;
	}


	/**
	 * 验证恢复命令、原账SHA和每个原份额的唯一全量逆向；来源、数量或单位不同即拒绝。
	 */
	private String restoreDocument(Reversal r, List<Restore> rows, Header h, List<Line> original) {
		verify(h, original);
		require(r != null && rows != null && rows.size() == original.size() && r.getId() != null && r.getId() > 0
						&& Objects.equals(r.getTenantId(), h.getTenantId()) && Objects.equals(r.getFactoryCode(), h.getFactoryCode())
						&& Objects.equals(r.getOperationId(), h.getId()) && r.getActorId() != null && r.getActorId() > 0
						&& Objects.equals(r.getCommandFingerprint(), codec.fingerprint(r.getCommandVersion(), r.getCommandSnapshot())),
				"NG恢复主账归属、原文或原份额全集不一致");
		var command = codec.objectDocument(r.getCommandSnapshot(), 64 * 1024);
		var keys = Set.of("version", "requestId", "expectedTenantId", "expectedFactoryCode", "expectedActorUserId", "operationId", "expectedFingerprint", "reason");
		require(command.size() == keys.size(), "NG恢复命令固定字段全集不同");
		for (String key : keys) {
			require(command.has(key) && command.get(key).isTextual(), "NG恢复命令字段必须明确文本");
		}
		require("MES_NG_MATERIAL_POLICY_RESTORE_COMMAND_V1".equals(r.getCommandVersion())
				&& Objects.equals(command.path("version").textValue(), r.getCommandVersion())
				&& Objects.equals(command.path("requestId").textValue(), r.getRequestId())
				&& r.getRequestId() != null && r.getRequestId().matches("[A-Za-z0-9_-]{8,64}")
				&& Objects.equals(command.path("expectedTenantId").textValue(), r.getTenantId().toString())
				&& Objects.equals(command.path("expectedFactoryCode").textValue(), r.getFactoryCode())
				&& Objects.equals(command.path("expectedActorUserId").textValue(), r.getActorId().toString())
				&& Objects.equals(command.path("operationId").textValue(), h.getId().toString())
				&& Objects.equals(command.path("expectedFingerprint").textValue(), h.getEvidenceFingerprint()), "NG恢复命令与精确原账/实际身份不一致");
		codec.reason(command.path("reason").textValue());
		var seen = new HashSet<Long>();
		var ids = new HashSet<Long>();
		var flows = new HashSet<Long>();
		for (var row : rows) {
			require(row != null && row.getId() != null && row.getId() > 0 && ids.add(row.getId())
					&& seen.add(row.getAllocationId()) && Objects.equals(row.getReversalId(), r.getId())
					&& Objects.equals(row.getTenantId(), h.getTenantId()) && Objects.equals(row.getFactoryCode(), h.getFactoryCode())
					&& Objects.equals(row.getOperationId(), h.getId()), "NG恢复份额主键重复或归属错误");
			var line = original.stream().filter(l -> Objects.equals(l.getId(), row.getAllocationId())).findFirst().orElse(null);
			require(line != null && Objects.equals(line.getSourceId(), row.getSourceId())
					&& Objects.equals(line.getSourceFlowId(), row.getSourceFlowId()) && Objects.equals(line.getSourceKind(), row.getSourceKind())
					&& Objects.equals(line.getMatCode(), row.getMatCode()) && Objects.equals(line.getMatUnit(), row.getMatUnit())
					&& amount(row.getQuantity(), false).compareTo(line.getQuantity()) == 0, "NG恢复必须是精确原份额全量");
			var before = amount(row.getBeforeQuantity(), true);
			var after = amount(row.getAfterQuantity(), true);
			if ("FEED".equals(row.getSourceKind())) {
				require(row.getRestoreFlowId() == null && before.subtract(after).compareTo(row.getQuantity()) == 0,
						"NG投料恢复必须减少原used_num份额");
			} else {
				require(row.getRestoreFlowId() != null && row.getRestoreFlowId() > 0 && flows.add(row.getRestoreFlowId())
						&& after.subtract(before).compareTo(row.getQuantity()) == 0, "NG库存恢复必须增加原stockId份额并有唯一真实IN");
			}
		}
		var result = reversal(r);
		result.put("originalFingerprint", h.getEvidenceFingerprint());
		result.put("restores", rows.stream().sorted(Comparator.comparing(Restore::getId)).map(this::restore).toList());
		String raw = codec.snapshot(result);
		require(raw.length() <= 16 * 1024 * 1024, "NG完整恢复证据超限");
		return raw;
	}

	private Map<String, Object> reversal(Reversal row) {
		var facts = new LinkedHashMap<String, Object>();
		facts.put("id", row.getId() == null ? null : row.getId().toString());
		facts.put("tenantId", row.getTenantId() == null ? null : row.getTenantId().toString());
		facts.put("operationId", row.getOperationId() == null ? null : row.getOperationId().toString());
		facts.put("actorId", row.getActorId() == null ? null : row.getActorId().toString());
		facts.put("factoryCode", row.getFactoryCode());
		facts.put("requestId", row.getRequestId());
		facts.put("commandVersion", row.getCommandVersion());
		facts.put("commandSnapshot", row.getCommandSnapshot());
		facts.put("commandFingerprint", row.getCommandFingerprint());
		facts.put("evidenceVersion", row.getEvidenceVersion());
		return facts;
	}

	private Map<String, Object> restore(Restore row) {
		var facts = new LinkedHashMap<String, Object>();
		facts.put("id", row.getId() == null ? null : row.getId().toString());
		facts.put("tenantId", row.getTenantId() == null ? null : row.getTenantId().toString());
		facts.put("reversalId", row.getReversalId() == null ? null : row.getReversalId().toString());
		facts.put("operationId", row.getOperationId() == null ? null : row.getOperationId().toString());
		facts.put("allocationId", row.getAllocationId() == null ? null : row.getAllocationId().toString());
		facts.put("sourceId", row.getSourceId() == null ? null : row.getSourceId().toString());
		facts.put("sourceFlowId", row.getSourceFlowId() == null ? null : row.getSourceFlowId().toString());
		facts.put("restoreFlowId", row.getRestoreFlowId() == null ? null : row.getRestoreFlowId().toString());
		facts.put("factoryCode", row.getFactoryCode());
		facts.put("sourceKind", row.getSourceKind());
		facts.put("matCode", row.getMatCode());
		facts.put("matUnit", row.getMatUnit());
		facts.put("quantity", row.getQuantity() == null ? null : codec.amount(row.getQuantity()));
		facts.put("beforeQuantity", row.getBeforeQuantity() == null ? null : codec.amount(row.getBeforeQuantity()));
		facts.put("afterQuantity", row.getAfterQuantity() == null ? null : codec.amount(row.getAfterQuantity()));
		return facts;
	}

}
