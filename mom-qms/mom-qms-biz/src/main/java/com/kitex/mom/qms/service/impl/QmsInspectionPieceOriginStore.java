package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;

import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO;
import com.kitex.mom.qms.dto.QmsInspectionPieceDTO;
import com.kitex.mom.qms.dto.QmsInspectionPieceEntryDTO;
import com.kitex.mom.qms.mapper.QmsInspectionPieceMapper;
import com.kitex.mom.qms.mapper.QmsInspectionPieceOriginMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 本域接收时原件；不以以后查询到的入站记录补造原始采集依据。
 */
@Service
@RequiredArgsConstructor
public class QmsInspectionPieceOriginStore {
	private static final String VERSION = "QMS_PIECE_ORIGIN@1";
	private static final int MAX_BYTES = 512 * 1024;
	private final QmsInspectionPieceOriginMapper rows;

	/**
	 * 本域核实过程中的原来源投影，不接收客户端任意回写。
	 *
	 * @param schemaVersion      不可变协议版本；用于解释原件而非重新计算历史。
	 * @param piece              原逐件样本完整FULL值与判定。
	 * @param basisFingerprint   原FULL检验依据指纹，防止按当前标准重算。
	 * @param submissionId       原已完成采集请求记录ID字符串。
	 * @param requestId          永久幂等请求号；同号异事实拒绝。
	 * @param requestPayload     原采集命令规范序列化原文，不回填历史。
	 * @param requestFingerprint 原采集命令SHA-256。
	 * @param productionContext  原冻结生产工单与工程依据，可空时保持未知。
	 * @param resolved           采集当时实际核对的MES原响应与QMS投影。
	 * @param actorId            实际登记操作者ID，按字符串保存，不代表设备认证。
	 * @param capturedAt         QMS保存本次采集来源原件的服务端UTC纪元毫秒字符串，非物理测量时刻。
	 * @param originalInspection 采集时冻结的首轮归属及制造来源证据，可空。
	 */
	public record Snapshot(
			String schemaVersion,
			QmsInspectionPieceDTO.Piece piece,
			String basisFingerprint,
			String submissionId,
			String requestId,
			String requestPayload,
			String requestFingerprint,
			MesInspectionProductionBasisDTO.Evidence productionContext,
			QmsInspectionPieceEntryService.Resolved resolved,
			String actorId,
			String capturedAt,
			@com.fasterxml.jackson.annotation.JsonInclude(
					com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
			com.kitex.mom.mes.dto.MesInspectionFirstRoundDTO.View originalInspection) {
	}

	/**
	 * 核对本域来源结构是否完整安装；未安装返回false，部分就绪时拒绝降级。
	 */
	boolean installed() {
		int count = rows.installed();
		if (count == 0) {
			return false;
		}
		require(count == 1 && Objects.equals(rows.ready(), 1), "采集来源原件结构未就绪，禁止降级为当前核对");
		return true;
	}

	/**
	 * 仅在真实样本采集事务中保存不可变来源原件，核对原提交和操作者；写入失败整笔采集回滚，不补录历史。
	 */
	void save(
			QmsInspectionPieceService.Checked piece,
			MesInspectionProductionBasisDTO.Evidence context,
			QmsInspectionPieceEntryService.Resolved resolved,
			QmsInspectionPieceMapper.Row request,
			com.kitex.mom.mes.dto.MesInspectionFirstRoundDTO.View assignment) {
		require(TransactionSynchronizationManager.isActualTransactionActive(), "来源原件必须与实际采集同事务提交");
		require(installed(), "来源原件结构尚未安装");
		var actual = submission(piece);
		require(
				Objects.equals(actual.getId(), request.getId())
						&& Objects.equals(actual.getFingerprint(), request.getFingerprint())
						&& Objects.equals(actual.getPayload(), request.getPayload())
						&& Objects.equals(actual.getActorId(), SecurityUtils.getUser().getId())
						&& actual.getActorId().toString().equals(piece.piece().actorId()),
				"来源原件与本次原请求或采集人不一致");
		long now = System.currentTimeMillis();
		require(now >= Long.parseLong(piece.piece().occurredAt()), "本域采集时钟回退，禁止补造来源观察时间");
		var snapshot =
				new Snapshot(
						VERSION,
						piece.piece(),
						piece.row().getBasisFingerprint(),
						actual.getId().toString(),
						actual.getRequestId(),
						actual.getPayload(),
						actual.getFingerprint(),
						context,
						resolved,
						actual.getActorId().toString(),
						Long.toString(now),
						assignment);
		String payload = QmsInspectionBasisJson.write(snapshot);
		require(payload.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, "采集来源原件超过512KiB");
		var row = new QmsInspectionPieceOriginMapper.Row();
		row.setPieceId(piece.row().getId());
		row.setTaskId(piece.task().getId());
		row.setTenantId(piece.task().getTenantId());
		row.setFactoryCode(piece.task().getFactoryCode());
		row.setSubmissionId(actual.getId());
		row.setCreatedBy(actual.getActorId());
		row.setCapturedAt(now);
		row.setPayload(payload);
		row.setFingerprint(QmsInspectionBasisJson.sha(payload));
		verify(piece, context, row);
		require(rows.insert(row) == 1, "采集来源原件保存失败，整笔逐件采集回滚");
	}

	/**
	 * 读取并核对采集时原FULL样本、原提交、实际组织及SHA-256来源链；缺少原件返回null，不回填旧采集。
	 */
	QmsInspectionPieceEntryDTO.View read(
			QmsInspectionPieceService.Checked piece,
			MesInspectionProductionBasisDTO.Evidence context) {
		if (!installed()) {
			return null;
		}
		var row =
				rows.find(
						piece.task().getTenantId(),
						piece.task().getFactoryCode(),
						piece.task().getId(),
						piece.row().getId());
		if (row == null) {
			return null;
		} // 旧采集不补录；查询时仍明确标记CURRENT_READ。
		return verify(piece, context, row);
	}

	/**
	 * 核对原件指纹、原样本、原请求和实际组织后读取完整采集快照；旧记录无原件时返回null，不追认。
	 */
	Snapshot snapshot(
			QmsInspectionPieceService.Checked piece,
			MesInspectionProductionBasisDTO.Evidence context) {
		if (!installed()) {
			return null;
		}
		var row =
				rows.find(
						piece.task().getTenantId(),
						piece.task().getFactoryCode(),
						piece.task().getId(),
						piece.row().getId());
		if (row == null) {
			return null;
		}
		verify(piece, context, row);
		return QmsInspectionBasisJson.read(row.getPayload(), Snapshot.class);
	}

	/**
	 * 读取并核对唯一已完成原采集请求与原样本绑定；重复、缺失或指纹损坏时拒绝。
	 */
	private QmsInspectionPieceMapper.Row submission(QmsInspectionPieceService.Checked piece) {
		var all =
				rows.submissions(
						piece.task().getTenantId(),
						piece.task().getFactoryCode(),
						piece.task().getId(),
						piece.row().getId());
		require(all.size() == 1, "来源原件缺少唯一原始采集请求");
		var r = all.get(0);
		require(
				"COMPLETED".equals(r.getState())
						&& Objects.equals(r.getPieceId(), piece.row().getId())
						&& Objects.equals(r.getTaskId(), piece.task().getId())
						&& Objects.equals(r.getTenantId(), piece.task().getTenantId())
						&& Objects.equals(r.getFactoryCode(), piece.task().getFactoryCode())
						&& r.getPayload() != null
						&& Objects.equals(
						QmsInspectionBasisJson.sha(r.getPayload()), r.getFingerprint()),
				"原采集请求身份或原文损坏");
		return r;
	}

	/**
	 * 核对原采集快照、原请求、实际组织、服务端采集时间和冻结首轮归属；损坏或矛盾时拒绝。
	 */
	private QmsInspectionPieceEntryDTO.View verify(
			QmsInspectionPieceService.Checked piece,
			MesInspectionProductionBasisDTO.Evidence context,
			QmsInspectionPieceOriginMapper.Row row) {
		require(
				Objects.equals(row.getPieceId(), piece.row().getId())
						&& Objects.equals(row.getTaskId(), piece.task().getId())
						&& Objects.equals(row.getTenantId(), piece.task().getTenantId())
						&& Objects.equals(row.getFactoryCode(), piece.task().getFactoryCode())
						&& row.getPayload() != null
						&& row.getPayload().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES
						&& Objects.equals(
						QmsInspectionBasisJson.sha(row.getPayload()), row.getFingerprint()),
				"采集来源原件组织或指纹损坏");
		var s = QmsInspectionBasisJson.read(row.getPayload(), Snapshot.class);
		var r = submission(piece);
		require(
				VERSION.equals(s.schemaVersion())
						&& row.getPayload().equals(QmsInspectionBasisJson.write(s))
						&& piece.piece().equals(s.piece())
						&& Objects.equals(piece.row().getBasisFingerprint(), s.basisFingerprint())
						&& Objects.equals(r.getId(), row.getSubmissionId())
						&& r.getId().toString().equals(s.submissionId())
						&& Objects.equals(r.getRequestId(), s.requestId())
						&& Objects.equals(r.getFingerprint(), s.requestFingerprint())
						&& Objects.equals(r.getPayload(), s.requestPayload())
						&& Objects.equals(r.getActorId(), row.getCreatedBy())
						&& r.getActorId().toString().equals(s.actorId())
						&& Objects.equals(context, s.productionContext())
						&& row.getCapturedAt() != null
						&& row.getCapturedAt() > 0
						&& row.getCapturedAt().toString().equals(s.capturedAt())
						&& row.getCapturedAt() >= Long.parseLong(piece.piece().occurredAt())
						&& s.resolved() != null,
				"采集来源原件与原样本、请求或冻结生产依据不一致");
		var projected =
				QmsInspectionPieceEntryService.project(piece, context, s.resolved().mesResponse());
		require(projected.equals(s.resolved().view()), "采集来源原件与留存MES响应不一致");
		if (s.originalInspection() != null) {
			QmsInspectionPieceFirstPassService.validate(
					piece, context, r, s.resolved(), s.originalInspection());
		}
		return new QmsInspectionPieceEntryDTO.View(
				projected.schemaVersion(),
				projected.tenantId(),
				projected.factoryCode(),
				projected.taskId(),
				projected.pieceId(),
				projected.identifier(),
				projected.basisFingerprint(),
				projected.availability(),
				projected.entry(),
				false,
				"COLLECTION_SNAPSHOT",
				s.capturedAt());
	}
}
