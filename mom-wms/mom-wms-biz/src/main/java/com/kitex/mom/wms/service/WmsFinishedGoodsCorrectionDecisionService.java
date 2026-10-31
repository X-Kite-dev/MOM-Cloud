package com.kitex.mom.wms.service;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.Query;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.Receipt;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionCommand;
import com.kitex.mom.wms.repository.WmsFinishedGoodsCorrectionDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 仅核对永久办理履历；不会重新审核、写库存或触发跨服务回写。
 */
@Service
@RequiredArgsConstructor
public class WmsFinishedGoodsCorrectionDecisionService {

	private final WmsFinishedGoodsCorrectionDecisionRepository repository;
	private final PermissionService permissions;

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Receipt lookup(Query query) {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		var user = SecurityUtils.getUser();
		require(tenant != null && tenant > 0 && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && !text(factory).isEmpty()
				&& user != null && !text(user.getUsername()).isEmpty(), "请先登录并选择实际工厂");
		validate(query);
		String permission = query.action().equals("WITHDRAW") ? "wms_bill_add_in_product" : "wms_bill_review_in_product";
		require(permissions.hasPermission(permission) || permissions.hasPermission(permission + "_" + query.warehouseCode()),
				"当前账号无权核对该仓库的原办理结果");
		var found = repository.applications(tenant, factory, query.correctionId());
		require(found.size() == 1, "原红冲申请不存在或范围不一致，请保留原操作核对");
		var row = found.get(0);
		require("0".equals(row.deleted()) && query.noticeId().equals(row.noticeId()) && !text(row.number()).isEmpty(),
				"原申请证据不完整，请保留原操作核对");
		var lines = repository.lines(tenant, factory, row.id());
		// 与申请时冻结行数核对，不能用缺失部分仓库的残余明细认领整单办理结果。
		require(row.lineCount() != null && row.lineCount() > 0 && row.lineCount() <= 10000
						&& lines.size() == row.lineCount() && lines.stream().allMatch(line ->
						"0".equals(line.deleted()) && query.warehouseCode().equals(line.warehouse())),
				"原申请冻结仓库不一致，请从原仓库核对");
		var operations = repository.operations(tenant, factory, row.id(), query.requestId());
		require(operations.size() <= 1, "原办理履历不唯一，请联系管理员核对");
		if (operations.isEmpty()) {
			return receipt(query, tenant, factory, user.getUsername(), row.number(), null, null, null, null);
		}
		var operation = operations.get(0);
		boolean approval = query.action().equals("APPROVE");
		String expectedType = approval && "STALE".equals(operation.type()) ? "STALE"
				: approval ? "APPROVE_EXECUTE" : query.action();
		String after = switch (expectedType) {
			case "WITHDRAW" -> "WITHDRAWN";
			case "REJECT" -> "REJECTED";
			case "STALE" -> "STALE";
			default -> "EXECUTED";
		};
		String fingerprint = fingerprint(query);
		require("0".equals(operation.deleted()) && Objects.equals(user.getUsername(), operation.operator())
						&& fingerprint.equals(operation.fingerprint()) && expectedType.equals(operation.type())
						&& "SUBMITTED".equals(operation.before()) && after.equals(operation.after())
						&& after.equals(row.status()) && operation.time() != null,
				"原办理人、正文或永久履历不一致，请保留原操作核对");
		return receipt(query, tenant, factory, user.getUsername(), row.number(), operation.time().toString(),
				fingerprint, operation.type(), after);
	}

	private void validate(Query query) {
		require(query != null && Set.of("APPROVE", "WITHDRAW", "REJECT").contains(text(query.action()))
				&& query.correctionId() != null && query.correctionId() > 0 && query.noticeId() != null && query.noticeId() > 0
				&& !text(query.warehouseCode()).isEmpty() && query.warehouseCode().equals(query.warehouseCode().trim())
				&& query.warehouseCode().length() <= 64 && !query.warehouseCode().matches(".*[?*].*")
				&& !text(query.requestId()).isEmpty() && query.requestId().length() <= 100
				&& query.requestId().equals(query.requestId().trim()) && !text(query.reason()).isEmpty()
				&& query.reason().length() <= 500, "请保留完整的原动作、申请、仓库及办理正文");
		if (query.action().equals("APPROVE")) {
			require(query.requestId().equals("APPROVE:" + query.correctionId()) && query.reason().equals("批准执行"),
					"批准操作必须使用原申请的固定办理号");
		}
	}

	private String fingerprint(Query query) {
		if (query.action().equals("APPROVE")) {
			return hash("APPROVE|" + query.correctionId());
		}
		var command = new WmsFinishedGoodsReceiptCorrectionCommand();
		command.setRequestId(query.requestId());
		if (query.action().equals("WITHDRAW")) {
			command.setReason(query.reason().trim());
		} else {
			command.setRejectReason(query.reason().trim());
		}
		return hash(JSONUtil.toJsonStr(command));
	}

	private Receipt receipt(Query query, Long tenant, String factory, String actor, String number, String time,
	                        String fingerprint, String operation, String after) {
		return new Receipt(1, operation == null ? "NOT_CONFIRMED" : "REGISTERED", query.requestId(), tenant.toString(),
				factory, actor, query.warehouseCode(), query.noticeId().toString(), query.correctionId().toString(), number,
				time, null, fingerprint, operation == null ? null : payload(query), query.action(), operation,
				operation == null ? null : "SUBMITTED", after);
	}

	private String payload(Query query) {
		var data = new LinkedHashMap<String, Object>();
		data.put("requestId", query.requestId());
		data.put("warehouseCode", query.warehouseCode());
		data.put("noticeId", query.noticeId().toString());
		data.put("reason", query.reason().trim());
		data.put("qmsDecisionReversalId", null);
		data.put("qmsDecisionAuthorizationCode", null);
		data.put("stockSelections", List.of());
		data.put("action", query.action());
		data.put("correctionId", query.correctionId().toString());
		try {
			return new ObjectMapper().writeValueAsString(data);
		} catch (JsonProcessingException exception) {
			throw new CheckedException("原办理回执无法编码，请保留原操作");
		}
	}

	private String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private String text(String value) {
		return value == null ? "" : value.trim();
	}

	private void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
