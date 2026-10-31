package com.kitex.mom.wms.service;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionSubmissionDTO.Query;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionSubmissionDTO.Receipt;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionCommand;
import com.kitex.mom.wms.repository.WmsFinishedGoodsCorrectionSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 只核对原SUBMIT，不重新执行库存、质检授权或跨服务回冲。
 */
@Service
@RequiredArgsConstructor
public class WmsFinishedGoodsCorrectionSubmissionService {

	private final WmsFinishedGoodsCorrectionSubmissionRepository repository;
	private final PermissionService permissions;

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Receipt lookup(Query query) {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		var actor = SecurityUtils.getUser();
		require(tenant != null && tenant > 0 && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && !text(factory).isEmpty()
				&& actor != null && !text(actor.getUsername()).isEmpty(), "请先登录并选择实际工厂");
		require(query != null && query.command() != null && !text(query.warehouseCode()).isEmpty()
						&& query.warehouseCode().length() <= 64 && !query.warehouseCode().matches(".*[?*].*"),
				"请指定原申请及实际仓库");
		String warehouse = query.warehouseCode().trim();
		require(permissions.hasPermission("wms_bill_add_in_product")
						|| permissions.hasPermission("wms_bill_add_in_product_" + warehouse),
				"当前账号无权核对该仓库成品纠错申请");
		var command = query.command();
		Map<Long, BigDecimal> selected = selections(command);
		var found = repository.find(tenant, factory, command.getRequestId().trim());
		require(found.size() <= 1, "原申请记录不唯一，请联系管理员核对");
		if (found.isEmpty()) {
			return new Receipt(1, "NOT_CONFIRMED", command.getRequestId(), tenant.toString(), factory,
					actor.getUsername(), warehouse, command.getNoticeId().toString(), null, null, null, null, null,
					null);
		}
		var row = found.get(0);
		String fingerprint = fingerprint(command);
		require(Objects.equals(row.applicant(), actor.getUsername()), "请由原申请账号核对办理结果");
		require("0".equals(row.deleted()) && row.id() != null && row.id() > 0
						&& Objects.equals(row.noticeId(), command.getNoticeId())
						&& Objects.equals(row.reason(), command.getReason().trim())
						&& Objects.equals(row.fingerprint(), fingerprint)
						&& Objects.equals(row.authorizationId(), command.getQmsDecisionReversalId())
						&& Objects.equals(text(row.authorizationCode()), text(command.getQmsDecisionAuthorizationCode()))
						&& !text(row.number()).isEmpty() && row.time() != null && row.status() != null
						&& Set.of("SUBMITTED", "REJECTED", "WITHDRAWN", "STALE", "EXECUTED").contains(row.status()),
				"原申请内容或登记证据不完整，请保留原请求并联系管理员核对");
		var lines = repository.lines(tenant, factory, row.id());
		require(!lines.isEmpty() && lines.size() == selected.size() && Objects.equals(row.lineCount(), lines.size()),
				"原申请冻结明细不完整，请保留原请求");
		BigDecimal total = BigDecimal.ZERO;
		var remaining = new HashMap<>(selected);
		for (var line : lines) {
			BigDecimal expected = remaining.remove(line.stockId());
			require("0".equals(line.deleted()) && warehouse.equals(line.warehouse()) && expected != null
							&& line.quantity() != null && line.quantity().compareTo(expected) == 0,
					"原申请库存、仓库或数量不一致，请保留原请求");
			total = total.add(line.quantity());
		}
		require(row.quantity() != null && total.compareTo(row.quantity()) == 0, "原申请合计与冻结明细不一致");
		var operations = repository.operations(tenant, factory, row.id(), command.getRequestId().trim());
		require(operations.size() == 1, "原申请缺少唯一登记履历，请保留原请求");
		var operation = operations.get(0);
		require("0".equals(operation.deleted()) && fingerprint.equals(operation.fingerprint())
				&& actor.getUsername().equals(operation.operator()) && row.time().equals(operation.time())
				&& "SUBMITTED".equals(operation.afterStatus()), "原申请登记履历不一致，请保留原请求");
		return new Receipt(1, "REGISTERED", command.getRequestId(), tenant.toString(), factory,
				actor.getUsername(), warehouse, command.getNoticeId().toString(), row.id().toString(), row.number(),
				row.time().toString(), number(total), fingerprint, payload(command));
	}

	private Map<Long, BigDecimal> selections(WmsFinishedGoodsReceiptCorrectionCommand command) {
		require(command.getNoticeId() != null && command.getNoticeId() > 0
				&& !text(command.getRequestId()).isEmpty() && command.getRequestId().length() <= 64
				&& !text(command.getReason()).isEmpty() && command.getReason().trim().length() <= 500
				&& command.getStockSelections() != null && !command.getStockSelections().isEmpty()
				&& command.getStockSelections().size() <= 1000, "请保留完整的原申请、原因及库存选择");
		var selected = new HashMap<Long, BigDecimal>();
		for (var line : command.getStockSelections()) {
			require(line != null && line.getStockId() != null && line.getStockId() > 0
							&& line.getQuantity() != null && line.getQuantity().signum() > 0
							&& line.getQuantity().stripTrailingZeros().scale() <= 8
							&& line.getQuantity().compareTo(new BigDecimal("1000000000000")) < 0
							&& selected.putIfAbsent(line.getStockId(), line.getQuantity()) == null,
					"原库存选择或数量无效，请保留原请求核对");
		}
		return selected;
	}

	private String payload(WmsFinishedGoodsReceiptCorrectionCommand command) {
		var data = new LinkedHashMap<String, Object>();
		data.put("noticeId", command.getNoticeId().toString());
		data.put("requestId", command.getRequestId());
		data.put("reason", command.getReason().trim());
		data.put("rejectReason", command.getRejectReason());
		data.put("qmsDecisionReversalId", command.getQmsDecisionReversalId() == null ? null
				: command.getQmsDecisionReversalId().toString());
		data.put("qmsDecisionAuthorizationCode", command.getQmsDecisionAuthorizationCode());
		data.put("stockSelections", command.getStockSelections().stream().map(line ->
				Map.of("stockId", line.getStockId().toString(), "quantity", number(line.getQuantity()))).toList());
		try {
			return new ObjectMapper().writeValueAsString(data);
		} catch (JsonProcessingException exception) {
			throw new CheckedException("原申请回执无法编码，请保留原请求");
		}
	}

	private String fingerprint(WmsFinishedGoodsReceiptCorrectionCommand command) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(JSONUtil.toJsonStr(command).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private String text(String value) {
		return value == null ? "" : value.trim();
	}

	private String number(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
