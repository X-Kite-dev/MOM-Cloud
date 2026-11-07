package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.dto.MesPickingWmsV1DTO.*;
import com.kitex.mom.mes.mapper.MesPickingWmsV1Mapper;
import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO.*;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPickingWmsCanonical.*;

/**
 * 纯V1封套及永久证明核验；完整规范原文/SHA与逐行身份共同匹配，不凭行数签成功。
 */
public final class MesPickingWmsProof {
	private MesPickingWmsProof() {
	}

	/**
	 * 本域永久正文；SUBMIT完整分仓，CANCEL独立原文，不保存人员令牌。
	 */
	public record Frozen(int contractVersion, String action, List<Command> commands, Cancel cancel) {
	}

	/**
	 * 从本域已存原文恢复，不从今日单据/BOM重建；哈希不符停止运输。
	 */
	public static Frozen frozen(MesPickingWmsV1Mapper.Command row) {
		require(row.getPayload() != null && hash(row.getPayload()).equals(row.getPayloadSha256()),
				"领料原命令正文或SHA已变化");
		Frozen value = read(row.getPayload(), Frozen.class);
		require(value.contractVersion() == 1 && row.getAction().equals(value.action()) && value.commands() != null,
				"领料永久命令版本或动作不一致");
		Scope scope = new Scope(row.getTenantId(), row.getFactoryCode());
		if ("SUBMIT".equals(value.action())) {
			require(value.cancel() == null && !value.commands().isEmpty() && value.commands().size() <= 20,
					"领料原批量封套无效");
			Set<String> warehouses = new HashSet<>();
			Set<String> requests = new HashSet<>();
			Set<Long> lines = new HashSet<>();
			int total = 0;
			for (Command command : value.commands()) {
				var prepared = prepare(command, scope, false);
				require(Objects.equals(command.getSourceRevision(), row.getSourceRevision())
								&& Objects.equals(command.getNotice().getDocumentId(), row.getBillId())
								&& warehouses.add(command.getNotice().getWarehouseCode()) &&
								requests.add(command.getRequestId()),
						"原批量来源、修订、仓库或请求号重复");
				for (var line : prepared.notice().getDetailList()) {
					require(lines.add(line.getSourceDocumentDetailId()), "同原领料明细不能跨仓重复受理");
					total++;
				}
			}
			require(total <= 500, "原批量超过500行");
		} else {
			require("CANCEL".equals(value.action()) && value.commands().isEmpty() && value.cancel() != null,
					"原撤回封套无效");
			cancelPayload(value.cancel(), scope);
			require(Objects.equals(value.cancel().getDocumentId(), row.getBillId())
					&& Objects.equals(value.cancel().getSourceRevision(), row.getSourceRevision())
					&& row.getRequestId().equals(value.cancel().getRequestId()), "原撤回来源或请求号不一致");
		}
		return value;
	}

	/**
	 * 校验完整受理或撤回证明并按原仓顺序固定结果；既有首次人员/原始请求别名合法保留。
	 */
	public static List<Receipt> verify(MesPickingWmsV1Mapper.Command row, List<Receipt> proofs,
	                                   List<Receipt> knownSubmission) {
		Frozen frozen = frozen(row);
		require(proofs != null, "未取得WMS永久证明");
		Scope scope = new Scope(row.getTenantId(), row.getFactoryCode());
		if ("CANCEL".equals(frozen.action())) {
			require(proofs.size() == 1, "撤回永久证明数量不一致");
			Receipt proof = proofs.get(0);
			base(proof, row);
			String payload = cancelPayload(frozen.cancel(), scope);
			require("CANCEL".equals(proof.action()) && "CANCELLED".equals(proof.status())
					&& row.getRequestId().equals(proof.requestId()) &&
					row.getRequestId().equals(proof.originalRequestId())
					&& "".equals(proof.warehouseCode()) && proof.noticeId() == null && proof.lines() != null
					&& proof.lines().isEmpty() && payload.equals(proof.requestPayload())
					&& hash(payload).equals(proof.contentFingerprint())
					&& text(frozen.cancel().getReason()).equals(proof.reason()), "撤回证明与原命令完整内容不一致");
			require(proof.cancelledNoticeIds() != null && proof.cancelledNoticeIds().size() <= 20,
					"撤回通知集合缺失或超限");
			Set<Long> cancelled = new HashSet<>();
			for (Long notice : proof.cancelledNoticeIds()) {
				require(notice != null && notice > 0 && cancelled.add(notice), "撤回通知ID无效或重复");
			}
			if (knownSubmission != null && !knownSubmission.isEmpty()) {
				Set<Long> known = new HashSet<>();
				knownSubmission.forEach(accepted -> known.add(accepted.noticeId()));
				require(cancelled.equals(known), "撤回通知集合与已知完整受理通知不一致");
			}
			return List.copyOf(proofs);
		}
		require(proofs.size() == frozen.commands().size(), "WMS未返回完整分仓受理证明");
		Map<String, Receipt> byRequest = new HashMap<>();
		Set<Long> noticeIds = new HashSet<>();
		Set<Long> detailIds = new HashSet<>();
		for (Receipt proof : proofs) {
			base(proof, row);
			require(byRequest.put(proof.requestId(), proof) == null && proof.noticeId() != null && proof.noticeId() > 0
					&& noticeIds.add(proof.noticeId()), "分仓证明请求或通知ID重复/缺失");
		}
		List<Receipt> result = new ArrayList<>();
		for (Command command : frozen.commands()) {
			var prepared = prepare(command, scope, false);
			Receipt proof = byRequest.get(command.getRequestId());
			require(proof != null && "ACCEPT".equals(proof.action()) && "ACCEPTED".equals(proof.status())
					&& command.getNotice().getWarehouseCode().equals(proof.warehouseCode())
					&& prepared.commandPayload().equals(proof.requestPayload())
					&& prepared.fingerprint().equals(proof.contentFingerprint())
					&& proof.cancelledNoticeIds() != null && proof.cancelledNoticeIds().isEmpty()
					&& "".equals(proof.reason()), "受理永久证明与原完整规范正文/SHA不一致");
			request(proof.originalRequestId());
			require(proof.lines() != null && proof.lines().size() == command.getNotice().getDetailList().size(),
					"受理原行集合缺失");
			Map<Long, Line> lines = new HashMap<>();
			for (Line line : proof.lines()) {
				require(line != null && line.sourceDocumentDetailId() != null && line.sourceDocumentDetailId() > 0
								&& lines.put(line.sourceDocumentDetailId(), line) == null && line.noticeDetailId() != null
								&& line.noticeDetailId() > 0 && detailIds.add(line.noticeDetailId()),
						"受理原行或真实通知行ID重复/无效");
			}
			for (var expected : command.getNotice().getDetailList()) {
				Line actual = lines.get(expected.getSourceDocumentDetailId());
				require(actual != null && expected.getMatCode().trim().equals(actual.materialCode())
								&& expected.getMatUnit().trim().equals(actual.unit()) && actual.quantity() != null
								&& actual.quantity().compareTo(BigDecimal.valueOf(expected.getNoticeOutNum())) == 0,
						"受理逐原行物料、单位或精确数量不一致");
			}
			result.add(proof);
		}
		return List.copyOf(result);
	}

	/**
	 * 任何证明均要求真实组织、原来源修订及首次受理人员/时间完整。
	 */
	private static void base(Receipt proof, MesPickingWmsV1Mapper.Command row) {
		require(proof != null && proof.contractVersion() == 1 && Objects.equals(proof.tenantId(), row.getTenantId())
						&& row.getFactoryCode().equals(proof.factoryCode()) && SOURCE.equals(proof.documentTable())
						&& Objects.equals(proof.documentId(), row.getBillId()) &&
						proof.sourceRevision() == row.getSourceRevision()
						&& proof.acceptedTime() != null && proof.acceptedBy() != null && !proof.acceptedBy().isBlank(),
				"WMS永久证明版本、组织、来源或受理人员时间不完整");
	}

	/**
	 * 读取已存回执仍复核正文SHA及全部证据，不能仅凭ACCEPTED标志。
	 */
	public static List<Receipt> receipts(MesPickingWmsV1Mapper.Command row) {
		require(row.getReceipt() != null && hash(row.getReceipt()).equals(row.getReceiptSha256()),
				"本地永久回执正文/SHA不一致");
		return Arrays.asList(read(row.getReceipt(), Receipt[].class));
	}

	/**
	 * 前端只获得字符串身份/标准单位数量，保留完整原文供按需追溯。
	 */
	public static CommandView view(MesPickingWmsV1Mapper.Command row) {
		Frozen frozen = frozen(row);
		List<Receipt> proofs = "ACCEPTED".equals(row.getState()) ? verify(row, receipts(row), List.of()) : List.of();
		Map<String, Receipt> byRequest = new HashMap<>();
		proofs.forEach(proof -> byRequest.put(proof.requestId(), proof));
		List<WarehouseView> warehouses = new ArrayList<>();
		for (Command command : frozen.commands()) {
			Receipt proof = byRequest.get(command.getRequestId());
			Map<Long, Line> lines = new HashMap<>();
			if (proof != null) {
				proof.lines().forEach(line -> lines.put(line.sourceDocumentDetailId(), line));
			}
			List<LineView> views = command.getNotice().getDetailList().stream().map(line -> {
				Line accepted = lines.get(line.getSourceDocumentDetailId());
				return new LineView(line.getSourceDocumentDetailId().toString(),
						accepted == null ? null : accepted.noticeDetailId().toString(),
						line.getMatCode(), line.getMatUnit(),
						BigDecimal.valueOf(line.getNoticeOutNum()).stripTrailingZeros().toPlainString());
			}).toList();
			warehouses.add(new WarehouseView(command.getRequestId(), command.getNotice().getWarehouseCode(),
					command.getNotice().getWarehouseName(), proof == null ? null : proof.noticeId().toString(),
					proof == null ? "PREPARED" : "ACCEPTED", views));
		}
		return new CommandView(row.getRequestId(), row.getAction(), row.getSourceRevision(), row.getState(),
				row.getOperatorId().toString(), row.getOperatorName(), row.getCreateTime().toString(),
				row.getUpdateTime().toString(),
				row.getPayloadSha256(), row.getPayload(), row.getReceiptSha256(), row.getReceipt(), warehouses);
	}
}
