package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;
import static com.kitex.mom.qms.service.impl.QmsInspectionPieceService.id;

import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.mes.api.feign.RemoteMesInspectionPieceEntryService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.qms.dto.QmsInspectionPieceEntryDTO;
import com.kitex.mom.qms.dto.QmsInspectionPieceEntryDTO.*;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 实际逐件样本核对MES原入站；不改变检验结果、计数或首次资格。
 */
@Service
@RequiredArgsConstructor
public class QmsInspectionPieceEntryService {
	private final QmsInspectionPieceService pieces;
	private final QmsInspectionBasisService basis;
	private final QmsInspectionHistorySupport access;
	private final PermissionService permissions;
	private final RemoteMesInspectionPieceEntryService remote;
	private final QmsInspectionPieceOriginStore origins;
	private final QmsInspectionPieceFirstPassService firstPass;

	/**
	 * 核对实际任务和单件后优先读取采集时冻结来源；旧件无原件时仅给出当前核对结果，不补造采集依据。
	 *
	 * @param taskId 当前实际工厂检验任务ID字符串
	 * @param pieceId 该任务原单件ID字符串
	 * @return 采集原件或明确标记的当前核对视图
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public View inspect(String taskId, String pieceId) {
		if (!permissions.hasPermission("procedureTask:show")) {
			throw new AccessDeniedException("没有查看工序检验的权限");
		}
		access.requireRead();
		var checked = pieces.checked(id(taskId), id(pieceId));
		var context = productionContext(checked);
		var saved = origins.read(checked, context);
		return saved != null ? saved : resolve(checked, context).view();
	}

	/**
	 * 本域核实过程中的原来源投影，不接收客户端任意回写。
	 *
	 * @param view        经过组织与原生产依据核对的本域入站投影。
	 * @param mesResponse 本次采集实际取得的MES原响应，不以以后查询补造。
	 */
	public record Resolved(View view, MesInspectionPieceEntryDTO.View mesResponse) {
	}

	/**
	 * 只在原始采集事务中调用，原号重放在进入此处前已经返回。
	 */
	void capture(
			QmsInspectionPieceService.Context collection,
			com.kitex.mom.qms.dto.QmsInspectionPieceDTO.Receipt receipt) {
		require(
				org.springframework.transaction.support.TransactionSynchronizationManager
						.isActualTransactionActive(),
				"来源原件必须随逐件采集事务提交");
		require(
				collection != null
						&& collection.replay() == null
						&& receipt != null
						&& "COMPLETED".equals(receipt.state())
						&& collection.piece().getId().toString().equals(receipt.pieceId()),
				"只能冻结本次真实采集，禁止补录旧回执");
		if (!origins.installed()) {
			return;
		}
		var checked = pieces.checked(collection.piece().getTaskId(), collection.piece().getId());
		var context = productionContext(checked);
		var resolved = resolve(checked, context);
		var assignment = firstPass.claim(checked, context, collection.request(), resolved);
		origins.save(checked, context, resolved, collection.request(), assignment);
	}

	/**
	 * 读取并核对样本所属原冻结生产依据，禁止后续版本替换原工单工程。
	 */
	private MesInspectionProductionBasisDTO.Evidence productionContext(
			QmsInspectionPieceService.Checked checked) {
		var frozen = basis.authorizedView("PROCEDURE", checked.task().getId());
		var context = frozen.original() == null ? null : frozen.original().productionContext();
		if (context != null) {
			QmsInspectionProductionBasisService.matches(context, frozen.current().task());
			require(
					Objects.equals(context, frozen.current().productionContext()),
					"检验后续版本替换了原生产依据");
		}
		return context;
	}

	/**
	 * 沿原人员身份读取MES实际入站，取得明确结果后保留原响应；不支持标识或未冻结依据保留不可用状态。
	 */
	private Resolved resolve(
			QmsInspectionPieceService.Checked checked,
			MesInspectionProductionBasisDTO.Evidence context) {
		if (context == null || !Set.of("FROZEN", "PARTIAL").contains(context.availability())) {
			return new Resolved(project(checked, context, null), null);
		}
		if (!checked.piece().identifier().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}")) {
			return new Resolved(project(checked, context, null), null);
		}
		var response =
				remote.query(
						new MesInspectionPieceEntryDTO.Query(
								context.requestId(),
								context.commandFingerprint(),
								context.operationCode(),
								checked.piece().identifier()));
		require(
				response != null && response.isOk() && response.getData() != null,
				"MES入站来源未取得明确结果，请刷新核对");
		var source = response.getData();
		return new Resolved(project(checked, context, source), source);
	}

	/**
	 * 核对MES原响应与实际样本、原工单工序、稳定节点和冻结依据一致后投影；矛盾或不明确响应拒绝。
	 */
	static View project(
			QmsInspectionPieceService.Checked checked,
			MesInspectionProductionBasisDTO.Evidence context,
			MesInspectionPieceEntryDTO.View source) {
		if (context == null || !Set.of("FROZEN", "PARTIAL").contains(context.availability())) {
			require(source == null, "不可用生产依据不能夹带MES响应");
			return view(checked, "PRODUCTION_BASIS_UNAVAILABLE", null);
		}
		if (!checked.piece().identifier().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}")) {
			require(source == null, "非MES单件标识不能借用同名入站响应");
			return view(checked, "IDENTIFIER_UNSUPPORTED", null);
		}
		require(source != null, "采集来源缺少已确认的MES响应");
		require(
				MesInspectionPieceEntryDTO.SCHEMA.equals(source.schemaVersion())
						&& context.tenantId().equals(source.tenantId())
						&& context.factoryCode().equals(source.factoryCode())
						&& checked.piece().identifier().equals(source.identifier())
						&& context.equals(source.productionBasis()),
				"MES入站来源与本件原生产依据不一致");
		require(
				source.availability() != null
						&& Set.of(
								"MATCHED",
								"NOT_RECORDED",
								"AMBIGUOUS",
								"SCHEMA_UNAVAILABLE",
								"PRODUCTION_BASIS_UNAVAILABLE")
						.contains(source.availability()),
				"MES入站来源状态无效");
		var e = source.entry();
		Entry entry = null;
		if ("MATCHED".equals(source.availability())) {
			require(
					e != null
							&& primary(e.sessionId())
							&& context.orderCode().equals(e.taskCode())
							&& context.operationCode().equals(e.produceTaskCode())
							&& context.routeNodeCode().equals(e.sourceRouteNodeCode())
							&& e.workerCode() != null
							&& !e.workerCode().isBlank()
							&& e.requestId() != null
							&& e.requestId().matches("[A-Za-z0-9._:-]{16,100}")
							&& e.fingerprint() != null
							&& e.fingerprint().matches("[0-9a-f]{64}"),
					"MES入站记录与原检验工单工序不符");
			try {
				LocalDateTime.parse(e.capturedAt());
			} catch (RuntimeException invalid) {
				throw new com.kitex.common.core.exception.CheckedException("MES入站记录开始时间无效");
			}
			entry =
					new Entry(
							e.sessionId(),
							e.taskCode(),
							e.produceTaskCode(),
							e.sourceRouteNodeCode(),
							e.workerCode(),
							e.worksiteCode(),
							e.requestId(),
							e.capturedAt(),
							e.fingerprint());
		} else {
			require(e == null, "未匹配的MES来源不能携带候选入站");
		}
		return view(checked, source.availability(), entry);
	}

	/**
	 * 按已核实原状态构造只读投影，不在此授予原首轮或改写样本。
	 */
	private static View view(QmsInspectionPieceService.Checked p, String state, Entry entry) {
		return new View(
				QmsInspectionPieceEntryDTO.SCHEMA,
				p.task().getTenantId().toString(),
				p.task().getFactoryCode(),
				p.task().getId().toString(),
				p.piece().id(),
				p.piece().identifier(),
				p.row().getBasisFingerprint(),
				state,
				entry,
				false,
				"CURRENT_READ",
				null);
	}

	/**
	 * 核对原ID是否为合法正长整数文本，不将缺失或溢出解释为有效ID。
	 */
	private static boolean primary(String value) {
		try {
			return value != null && value.matches("[1-9][0-9]{0,18}") && Long.parseLong(value) > 0;
		} catch (NumberFormatException ex) {
			return false;
		}
	}
}
