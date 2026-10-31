package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.enums.menu.ReturnReceiptActionEnum;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsProductionReturnReceiptCommand;
import com.kitex.mom.wms.dto.app.WmsReturnFinalizeDTO.Command;
import com.kitex.mom.wms.dto.app.WmsReturnFinalizeDTO.MobileContext;
import com.kitex.mom.wms.dto.app.WmsReturnFinalizeDTO.Result;
import com.kitex.mom.wms.dto.app.WmsReturnFinalizeDTO.View;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsProductionReturnReceiptOperationEntity;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.mapper.WmsProductionReturnReceiptOperationMapper;
import com.kitex.mom.wms.mapper.WmsReturnFinalizeMapper;
import com.kitex.mom.wms.mapper.WmsReturnFinalizeMapper.Receipt;
import com.kitex.mom.wms.service.impl.WmsProductionReturnReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * 原请求、实际退料终结及结果封存同一事务；已确认结果不随后续差异处置变化。
 */
@Service
@RequiredArgsConstructor
public class WmsReturnFinalizeService {

	private final WmsReturnFinalizeMapper receipts;
	private final WmsInNoticeMapper notices;
	private final WmsProductionReturnReceiptOperationMapper operations;
	private final WmsProductionReturnReceiptService business;
	private final WmsReturnReceiptAccessService access;
	private final ObjectMapper json;
	private final WmsWarehouseBusinessGuard warehouseGuard;

	@Transactional(rollbackFor = Exception.class)
	public View finalizeReceipt(Command command) {
		return execute(command, null, false);
	}

	@Transactional(rollbackFor = Exception.class)
	public View finalizeMobile(Command command) {
		return execute(command, null, true);
	}

	public MobileContext mobileContext(Long noticeId, String warehouseCode) {
		Scope scope = scope();
		requireMobileIdentity(scope);
		String warehouse = warehouse(warehouseCode);
		checkNotice(notices.selectById(noticeId), noticeId, warehouse, scope);
		boolean enabled;
		String message;
		try {
			enabled = warehouseGuard.mobileEnabled(warehouse);
			message = enabled ? "" : "该仓库未开启手机端作业";
		} catch (RuntimeException unavailable) {
			// 只降低新作业的探测能力；原通知授权与实际写入校验不在此捕获范围。
			enabled = false;
			message = "仓库手机端配置暂不可用，请稍后重试";
		}
		return new MobileContext(1, 1, scope.tenant(), scope.factory(), scope.operator(),
				noticeId, warehouse, enabled, message);
	}

	private void requireMobileIdentity(Scope scope) {
		require(Objects.equals(SecurityUtils.getUser().getTenantId(), scope.tenant()),
				"登录租户与当前退料上下文不一致");
	}

	@Transactional(rollbackFor = Exception.class)
	public View cancel(Command command, String reason) {
		require(!text(reason).isEmpty() && text(reason).length() <= 500, "请填写500字以内的撤销原因");
		return execute(command, text(reason), false);
	}

	/**
	 * 旧 URL 保留 View 响应，写入也进入永久原请求保护。
	 */
	@Transactional(rollbackFor = Exception.class)
	public WmsProductionReturnReceiptCommand.View finalizeLegacy(WmsProductionReturnReceiptCommand.Finalize command) {
		require(command != null && command.getNoticeId() != null, "请选择生产退料通知");
		Scope scope = scope();
		WmsInNoticeEntity notice = notices.selectById(command.getNoticeId());
		checkNotice(notice, command.getNoticeId(), notice == null ? "" : notice.getWarehouseCode(), scope);
		View receipt = execute(new Command(command.getRequestId(), command.getNoticeId(), notice.getWarehouseCode(),
				command.getOverageQuantity(), command.getReason()), null, false);
		require("CONFIRMED".equals(receipt.status()), "原终结请求已撤销，请重新核对收货");
		return business.finalizationView(command.getNoticeId());
	}

	public View lookup(String requestId, Long noticeId, String warehouseCode) {
		Scope scope = scope();
		checkRequestId(requestId);
		String warehouse = warehouse(warehouseCode);
		access.requirePermission(warehouse, ReturnReceiptActionEnum.FINALIZE);
		Receipt row = receipts.find(scope.tenant(), requestId);
		if (row == null) {
			checkNotice(notices.selectById(noticeId), noticeId, warehouse, scope);
			return new View(1, requestId, "NOT_CONFIRMED", noticeId, warehouse, null, null, null, null);
		}
		checkIdentity(row, scope, noticeId, warehouse);
		return view(row);
	}

	private View execute(Command command, String cancellationReason, boolean mobile) {
		Scope scope = scope();
		if (mobile) {
			requireMobileIdentity(scope);
		}
		Receipt expected = prepare(command, scope);
		access.requirePermission(expected.warehouseCode(), ReturnReceiptActionEnum.FINALIZE);
		Receipt row = receipts.find(scope.tenant(), command.requestId());
		if (row != null) {
			checkOriginal(row, expected, scope);
			return view(row);
		}
		receipts.reserve(expected);
		row = receipts.findForUpdate(scope.tenant(), command.requestId());
		require(row != null, "终结原请求未可靠保存");
		checkOriginal(row, expected, scope);
		if (!"PENDING".equals(row.status())) {
			return view(row);
		}
		require(notices.lockNotice(command.noticeId()) != null, "原退料通知不存在或不可访问");
		checkNotice(notices.selectById(command.noticeId()), command.noticeId(), expected.warehouseCode(), scope);
		Result result = historical(command, scope);
		if (result == null && cancellationReason == null) {
			if (mobile) {
				warehouseGuard.checkMobile(expected.warehouseCode());
			}
			var response = business.finalizeReceipt(new WmsProductionReturnReceiptCommand.Finalize()
					.setRequestId(command.requestId()).setNoticeId(command.noticeId())
					.setOverageQuantity(command.overageQuantity()).setReason(text(command.reason())));
			require(response != null && response.getNotice() != null
					&& Objects.equals(response.getNotice().getId(), command.noticeId()), "退料终结结果不完整");
			result = new Result(response.getNotice().getReturnReceiptStatus(), response.getActualReceivedQuantity(),
					response.getShortageQuantity(), response.getOverageQuantity());
		}
		if (result != null) {
			validateResult(result);
			require(result.overageQuantity().compareTo(quantity(command.overageQuantity())) == 0,
					"终结结果与原申报溢收数量不一致");
		}
		String status = result == null ? "CANCELLED" : "CONFIRMED";
		require(receipts.complete(scope.tenant(), scope.factory(), scope.operator(), command.requestId(),
				expected.requestFingerprint(), status, result == null ? null : encode(result), LocalDateTime.now(),
				result == null ? cancellationReason : null) == 1, "退料终结回执保存失败，整笔操作回滚");
		return view(receipts.find(scope.tenant(), command.requestId()));
	}

	/**
	 * 新表上线前已存在的请求，只有原人、原通知及完整原快照一致才可补封存回执。
	 */
	private Result historical(Command command, Scope scope) {
		var row = operations.selectOne(new LambdaQueryWrapper<WmsProductionReturnReceiptOperationEntity>()
				.eq(WmsProductionReturnReceiptOperationEntity::getRequestId, command.requestId()));
		if (row == null) {
			return null;
		}
		require(Objects.equals(row.getTenantId(), scope.tenant())
				&& Objects.equals(row.getNoticeId(), command.noticeId()) && "FINALIZE".equals(row.getOperationType())
				&& Objects.equals(row.getFactoryCode(), scope.factory())
				&& Objects.equals(row.getOperatorCode(), scope.operator()), "原请求已被其他收货操作使用");
		try {
			JsonNode snapshot = json.readTree(row.getDetail());
			JsonNode original = snapshot.path("notice");
			require(original.path("id").asText().equals(command.noticeId().toString())
							&& original.path("warehouseCode").asText().equals(warehouse(command.warehouseCode()))
							&& original.path("returnOverageNum").isNumber()
							&& original.path("returnOverageNum").decimalValue().compareTo(quantity(command.overageQuantity())) == 0
							&& text(original.path("returnVarianceReason").asText()).equals(text(command.reason())),
					"历史终结快照与原请求不一致，请核对原收货履历");
			return new Result(row.getAfterStatus(), number(snapshot, "actualReceivedQuantity"),
					number(snapshot, "shortageQuantity"), number(snapshot, "overageQuantity"));
		} catch (JsonProcessingException | IllegalArgumentException exception) {
			throw new CheckedException("历史终结快照不完整，请核对原收货履历");
		}
	}

	private BigDecimal number(JsonNode node, String name) {
		require(node.hasNonNull(name) && node.path(name).isNumber(), "原终结数量快照不完整");
		return node.path(name).decimalValue();
	}

	private Receipt prepare(Command command, Scope scope) {
		require(command != null && command.noticeId() != null && command.noticeId() > 0, "请选择生产退料通知");
		checkRequestId(command.requestId());
		String warehouse = warehouse(command.warehouseCode());
		BigDecimal overage = quantity(command.overageQuantity());
		require(text(command.reason()).length() <= 500, "现场原因不能超过500字");
		var payload = new LinkedHashMap<String, Object>();
		payload.put("noticeId", command.noticeId().toString());
		payload.put("warehouseCode", warehouse);
		payload.put("overageQuantity", overage);
		payload.put("reason", text(command.reason()));
		String encoded = encode(payload);
		try {
			String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(encoded.getBytes(StandardCharsets.UTF_8)));
			return new Receipt(scope.tenant(), scope.factory(), scope.operator(), command.requestId(), command.noticeId(),
					warehouse, encoded, fingerprint, "PENDING", null, null, null);
		} catch (NoSuchAlgorithmException exception) {
			throw new CheckedException("终结原请求无法编码");
		}
	}

	private void checkOriginal(Receipt row, Receipt expected, Scope scope) {
		checkIdentity(row, scope, expected.noticeId(), expected.warehouseCode());
		require(Objects.equals(row.requestFingerprint(), expected.requestFingerprint())
				&& Objects.equals(row.requestPayload(), expected.requestPayload()), "终结原请求号已被不同内容使用");
	}

	private void checkIdentity(Receipt row, Scope scope, Long noticeId, String warehouse) {
		require(Objects.equals(row.tenantId(), scope.tenant()) && Objects.equals(row.factoryCode(), scope.factory())
				&& Objects.equals(row.operatorCode(), scope.operator()) && Objects.equals(row.noticeId(), noticeId)
				&& Objects.equals(row.warehouseCode(), warehouse), "当前身份与原退料终结请求不一致");
	}

	private void checkNotice(WmsInNoticeEntity notice, Long noticeId, String warehouse, Scope scope) {
		require(notice != null && Objects.equals(notice.getId(), noticeId)
						&& Objects.equals(notice.getTenantId(), scope.tenant())
						&& Objects.equals(notice.getFactoryCode(), scope.factory())
						&& Objects.equals(notice.getWarehouseCode(), warehouse)
						&& "return".equals(notice.getBillType())
						&& "mom_mes.mes_warehouse_stock_feed_judge".equals(notice.getDocumentTable()),
				"当前租户、工厂和仓库下不存在原生产退料通知");
		access.requireNotice(notice, ReturnReceiptActionEnum.FINALIZE);
	}

	private Scope scope() {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		var actor = SecurityUtils.getUser();
		require(tenant != null && tenant > 0 && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && !text(factory).isEmpty()
				&& actor != null && !text(actor.getUsername()).isEmpty(), "结束收货需要实际租户、工厂和登录账号");
		return new Scope(tenant, factory, actor.getUsername());
	}

	private View view(Receipt row) {
		require(row != null, "原退料终结回执未可靠保存");
		Result result = null;
		if ("CONFIRMED".equals(row.status())) {
			try {
				result = json.readValue(row.resultPayload(), Result.class);
				validateResult(result);
			} catch (JsonProcessingException exception) {
				throw new CheckedException("原退料终结回执损坏，请核对收货履历");
			}
		}
		String status = "PENDING".equals(row.status()) ? "NOT_CONFIRMED" : row.status();
		return new View(1, row.requestId(), status, row.noticeId(), row.warehouseCode(),
				row.requestPayload(), result, row.completedTime(), row.cancellationReason());
	}

	private void validateResult(Result result) {
		require(result != null && result.returnReceiptStatus() != null
						&& Set.of("RECEIVED", "VARIANCE_PENDING").contains(result.returnReceiptStatus())
						&& result.actualReceivedQuantity() != null && result.actualReceivedQuantity().signum() >= 0
						&& result.shortageQuantity() != null && result.shortageQuantity().signum() >= 0
						&& result.overageQuantity() != null && result.overageQuantity().signum() >= 0,
				"退料终结状态或数量快照不完整");
		boolean shortage = result.shortageQuantity().signum() > 0;
		boolean overage = result.overageQuantity().signum() > 0;
		require("RECEIVED".equals(result.returnReceiptStatus()) ? !shortage && !overage : shortage != overage,
				"退料终结状态与差异数量不一致");
	}

	private String encode(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new CheckedException("退料终结原请求或回执无法编码");
		}
	}

	private static BigDecimal quantity(BigDecimal value) {
		BigDecimal number = value == null ? BigDecimal.ZERO : value.stripTrailingZeros();
		require(number.signum() >= 0 && number.scale() <= 8 && number.precision() - number.scale() <= 10,
				"溢收数量须为有效非负数，最多8位小数和10位整数");
		return number;
	}

	private static String warehouse(String value) {
		String code = text(value);
		require(!code.isEmpty() && code.length() <= 64 && !code.contains("*") && !code.contains("?"),
				"请指定实际收货仓库");
		return code;
	}

	private static void checkRequestId(String value) {
		require(value != null && value.trim().length() >= 16 && value.trim().length() <= 64
				&& value.equals(value.trim()) && !value.contains(":"), "原退料终结请求号无效");
	}

	private static String text(String value) {
		return value == null ? "" : value.trim();
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	private record Scope(Long tenant, String factory, String operator) {
	}
}
