package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;
import static com.kitex.mom.qms.service.impl.QmsInspectionPieceService.id;

import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.mes.api.feign.RemoteMesInspectionFirstRoundService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.qms.dto.QmsInspectionPieceFirstPassDTO;
import com.kitex.mom.qms.mapper.QmsInspectionPieceMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/**
 * 核对制造出生、原采集归属、FULL原样本和复检链，明确单件首轮资格与未知来源。
 */
@Service
@RequiredArgsConstructor
public class QmsInspectionPieceFirstPassService {
	private final QmsInspectionPieceService pieces;
	private final QmsInspectionBasisService basis;
	private final QmsInspectionHistorySupport access;
	private final PermissionService permissions;
	private final QmsInspectionPieceOriginStore origins;
	private final RemoteMesInspectionFirstRoundService remote;
	private final QmsInspectionPieceReinspectionService reinspection;

	/**
	 * 核对实际操作者、制造出生、原入站和已确认QMS任务，永久占有同件同节点的原采集归属；同号同事实返回原件，同号异事实拒绝。
	 */
	MesInspectionFirstRoundDTO.View claim(
			QmsInspectionPieceService.Checked checked,
			MesInspectionProductionBasisDTO.Evidence context,
			QmsInspectionPieceMapper.Row request,
			QmsInspectionPieceEntryService.Resolved resolved) {
		if (context == null
				|| !"FROZEN".equals(context.availability())
				|| !"MATCHED".equals(resolved.view().availability())) {
			return null;
		}
		var response =
				remote.claim(
						new MesInspectionFirstRoundDTO.Claim(
								new MesInspectionPieceEntryDTO.Query(
										context.requestId(),
										context.commandFingerprint(),
										context.operationCode(),
										checked.piece().identifier()),
								checked.task().getId().toString(),
								request.getRequestId(),
								request.getFingerprint(),
								request.getActorId().toString()));
		require(
				response != null && response.isOk() && response.getData() != null,
				"首次检验归属未取得明确结果，请沿原采集请求恢复");
		validate(checked, context, request, resolved, response.getData());
		return response.getData();
	}

	/**
	 * 核对首轮归属协议、制造原件、实际样本原请求、冻结入站和服务端时间先后；任何矛盾均拒绝，不追认旧记录。
	 */
	static void validate(
			QmsInspectionPieceService.Checked checked,
			MesInspectionProductionBasisDTO.Evidence context,
			QmsInspectionPieceMapper.Row request,
			QmsInspectionPieceEntryService.Resolved resolved,
			MesInspectionFirstRoundDTO.View view) {
		require(
				view != null
						&& MesInspectionFirstRoundDTO.SCHEMA.equals(view.schemaVersion())
						&& Set.of(
								"ASSIGNED",
								"OTHER_INSPECTION",
								"NOT_MANUFACTURING_ID",
								"SCHEMA_UNAVAILABLE")
						.contains(Objects.toString(view.availability(), "")),
				"首次检验归属协议或状态无效");
		if (!"ASSIGNED".equals(view.availability())) {
			require(view.assignment() == null && view.fingerprint() == null, "未知或非首轮归属不能夹带正向资格");
			return;
		}
		var a = view.assignment();
		require(
				a != null
						&& Objects.equals(
						QmsInspectionBasisJson.sha(QmsInspectionBasisJson.write(a)),
						view.fingerprint()),
				"首次检验原归属指纹不符");
		var c = a.claim();
		var born = a.manufacturingPiece();
		var birth = born == null ? null : born.birth();
		require(
				MesInspectionFirstRoundDTO.SCHEMA.equals(a.schemaVersion())
						&& c != null
						&& birth != null
						&& MesManufacturingPieceDTO.SCHEMA.equals(birth.schemaVersion())
						&& Objects.equals(
						QmsInspectionBasisJson.sha(QmsInspectionBasisJson.write(birth)),
						born.fingerprint())
						&& birth.registeredAt() != null
						&& birth.registeredAt().matches("[1-9][0-9]{0,18}")
						&& Long.parseLong(checked.piece().occurredAt())
						>= Long.parseLong(birth.registeredAt())
						&& birth.generation() != null
						&& Objects.equals(
						birth.identifier(),
						com.kitex.mom.common.identifier.IdentifierCodeTemplate.render(
								birth.generation().template(),
								birth.generation().context(),
								java.time.LocalDateTime.parse(
										birth.generation().generatedAt()),
								Long.parseLong(birth.generation().sequence())))
						&& Objects.equals(birth.generation().context().tenant(), birth.tenantId())
						&& Objects.equals(
						birth.generation().context().factory(), birth.factoryCode())
						&& Objects.equals(
						birth.generation().context().material(), context.productCode())
						&& "OPERATOR_DECLARED_NEW_PIECE".equals(birth.identityBasis())
						&& birth.command().newPhysicalPieceDeclared()
						&& Objects.equals(birth.tenantId(), checked.task().getTenantId().toString())
						&& Objects.equals(birth.factoryCode(), checked.task().getFactoryCode())
						&& Objects.equals(birth.identifier(), checked.piece().identifier())
						&& Objects.equals(birth.orderId(), context.orderId())
						&& Objects.equals(birth.orderCode(), context.orderCode())
						&& Objects.equals(birth.engineeringHash(), context.engineeringHash())
						&& Objects.equals(
						birth.engineeringReleaseId(), context.engineeringReleaseId())
						&& Objects.equals(birth.quantityUnit(), context.quantityUnit())
						&& Objects.equals(c.qmsTaskId(), checked.task().getId().toString())
						&& Objects.equals(c.qmsRequestId(), request.getRequestId())
						&& Objects.equals(c.qmsRequestFingerprint(), request.getFingerprint())
						&& Objects.equals(c.qmsActorId(), request.getActorId().toString())
						&& Objects.equals(
						c.source(),
						new MesInspectionPieceEntryDTO.Query(
								context.requestId(),
								context.commandFingerprint(),
								context.operationCode(),
								checked.piece().identifier()))
						&& Objects.equals(a.entry(), resolved.mesResponse())
						&& "FROZEN".equals(context.availability())
						&& "MATCHED".equals(resolved.view().availability()),
				"首次检验原归属与实际件、原请求、冻结工程或来源不一致");
	}

	/**
	 * 将已核实归属投影为只读证据，保留原请求、制造指纹与服务端采集时刻；缺失归属保持null。
	 */
	private QmsInspectionPieceFirstPassDTO.Evidence proof(
			MesInspectionFirstRoundDTO.View v, String sampleCapturedAt) {
		if (v == null) {
			return null;
		}
		var a = v.assignment();
		return new QmsInspectionPieceFirstPassDTO.Evidence(
				v.schemaVersion(),
				v.availability(),
				v.fingerprint(),
				a == null ? null : a.manufacturingPiece().fingerprint(),
				a == null ? null : a.manufacturingPiece().birth().identityBasis(),
				a == null ? null : a.claim().qmsRequestId(),
				a == null ? null : a.claim().qmsRequestFingerprint(),
				a == null ? null : a.claim().qmsActorId(),
				a == null ? null : a.claim().source().requestId(),
				a == null ? null : a.entry().productionBasis().engineeringHash(),
				a == null ? null : a.manufacturingPiece().birth().registeredAt(),
				a == null ? null : sampleCapturedAt);
	}

	/**
	 * 核对当前实际工厂单件的制造出生、永久原首轮归属及原FULL样本；复检不计首轮，未证来源保留未知。
	 *
	 * @param taskId 原检验任务ID字符串
	 * @param pieceId 该任务单件ID字符串
	 * @return 首轮资格、按登记件数计量的数量及原证据
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public QmsInspectionPieceFirstPassDTO.View inspect(String taskId, String pieceId) {
		if (!permissions.hasPermission("procedureTask:show")) {
			throw new AccessDeniedException("没有查看工序检验的权限");
		}
		access.requireRead();
		return inspectAuthorized(taskId, pieceId);
	}

	/**
	 * 本域总体读取先完成权限校验，再逐件核对同一原样本、采集来源及FULL证据；组织范围仍逐项核对。
	 */
	QmsInspectionPieceFirstPassDTO.View inspectAuthorized(String taskId, String pieceId) {
		access.requireScope();
		var checked = pieces.checked(id(taskId), id(pieceId));
		var frozen = basis.authorizedView("PROCEDURE", checked.task().getId());
		var context = frozen.original() == null ? null : frozen.original().productionContext();
		if (context != null) {
			QmsInspectionProductionBasisService.matches(context, frozen.current().task());
			require(Objects.equals(context, frozen.current().productionContext()), "原生产依据已被替换");
		}
		var snapshot = origins.snapshot(checked, context);
		var assignment = snapshot == null ? null : snapshot.originalInspection();
		String relationState = null;
		if (checked.task().getSourceRequestId() != null
				&& checked.task().getSourceRequestId().startsWith("REWORK-REINSPECTION:")) {
			var relation = reinspection.inspectAuthorized(taskId, pieceId);
			relationState = relation.availability();
			require(!"ROOT_UNVERIFIED".equals(relationState), "复检任务不能作为原始首轮");
		}
		boolean excludedReinspection =
				relationState != null && !"SCHEMA_UNAVAILABLE".equals(relationState);
		boolean eligible =
				!excludedReinspection
						&& assignment != null
						&& "ASSIGNED".equals(assignment.availability());
		boolean qualified =
				checked.piece().samples().stream()
						.allMatch(s -> Boolean.TRUE.equals(s.qualified()));
		String qualification =
				excludedReinspection
						? "REINSPECTION"
						: eligible
						? "ELIGIBLE_FIRST_ROUND"
						: assignment != null
						&& "OTHER_INSPECTION"
						.equals(assignment.availability())
						? "NOT_ORIGINAL_ASSIGNMENT"
						: "UNVERIFIED";
		var gaps = new ArrayList<String>();
		if (excludedReinspection) {
			gaps.add("REINSPECTION_EXCLUDED");
		}
		if (snapshot == null) {
			gaps.add("COLLECTION_ORIGIN_MISSING");
		}
		if (assignment == null) {
			gaps.add("ORIGINAL_INSPECTION_ASSIGNMENT_MISSING");
		} else {
			if (!eligible) {
				gaps.add(assignment.availability());
			}
		}
		return new QmsInspectionPieceFirstPassDTO.View(
				QmsInspectionPieceFirstPassDTO.SCHEMA,
				checked.task().getTenantId().toString(),
				checked.task().getFactoryCode(),
				taskId,
				pieceId,
				checked.piece().identifier(),
				checked.row().getBasisFingerprint(),
				qualification,
				eligible,
				qualified,
				eligible
						? "1"
						: Set.of("NOT_ORIGINAL_ASSIGNMENT", "REINSPECTION").contains(qualification)
						? "0"
						: null,
				eligible
						? (qualified ? "1" : "0")
						: Set.of("NOT_ORIGINAL_ASSIGNMENT", "REINSPECTION").contains(qualification)
						? "0"
						: null,
				context == null ? null : context.quantityUnit(),
				context == null ? null : context.routeNodeCode(),
				gaps,
				relationState,
				proof(assignment, checked.piece().occurredAt()));
	}
}
