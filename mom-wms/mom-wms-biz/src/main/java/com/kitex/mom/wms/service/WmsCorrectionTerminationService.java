package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsCorrectionTerminationDTO.*;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionSubmissionDTO.Query;
import com.kitex.mom.wms.repository.WmsCorrectionTerminationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class WmsCorrectionTerminationService {
	private final WmsCorrectionTerminationRepository repository;
	private final WmsFinishedGoodsCorrectionSubmissionService submissions;
	private final PermissionService permissions;

	private record Scope(Long tenant, String factory, String operator, String warehouse) {
	}

	@Transactional(rollbackFor = Exception.class)
	public Outcome lookup(Query query) {
		var scope = scope(query);
		repository.lock(scope.tenant(), scope.factory(), query.command().getRequestId().trim());
		return outcome(query, scope);
	}

	@Transactional(rollbackFor = Exception.class)
	public Outcome end(EndCommand command) {
		require(command != null && command.reason() != null && !command.reason().trim().isEmpty() &&
				command.reason().trim().length() <= 500, "请填写500字以内的结束原因");
		var query = command.original();
		var scope = scope(query);
		repository.lock(scope.tenant(), scope.factory(), query.command().getRequestId().trim());
		var prior = outcome(query, scope);
		if ("REGISTERED".equals(prior.status())) {
			return prior;
		}
		if (prior.termination() != null) {
			require(Objects.equals(prior.termination().reason(), command.reason().trim()),
					"原请求已按其他结束原因登记，请先查询原结果");
			return prior;
		}
		require(Objects.equals(scope.warehouse(),
						repository.sourceWarehouse(scope.tenant(), scope.factory(), query.command().getNoticeId())),
				"原通知实际仓库与原申请不一致，不能结束");
		var ended = new Ended(IdWorker.getIdStr(), scope.tenant().toString(), scope.factory(), scope.operator(),
				scope.warehouse(),
				query.command().getNoticeId().toString(), query.command().getRequestId().trim(),
				submissions.fingerprint(query.command()),
				submissions.payload(query.command()), command.reason().trim(), timestamp(LocalDateTime.now()));
		require(repository.insert(ended) == 1, "原请求结束未确认，请保留原文并查询");
		return new Outcome(2, "ENDED", null, ended);
	}

	private Outcome outcome(Query query, Scope scope) {
		var registration = submissions.lookup(query);
		var ended = repository.find(scope.tenant(), scope.factory(), query.command().getRequestId().trim());
		require(!"REGISTERED".equals(registration.status()) || ended == null, "原登记与结束凭据冲突，请保留原文核对");
		if (ended == null) {
			return new Outcome(2, registration.status(), registration, null);
		}
		require(Objects.equals(ended.tenantId(), scope.tenant().toString()) &&
						Objects.equals(ended.factoryCode(), scope.factory())
						&& Objects.equals(ended.operatorCode(), scope.operator()) &&
						Objects.equals(ended.warehouseCode(), scope.warehouse())
						&& Objects.equals(ended.noticeId(), query.command().getNoticeId().toString()) &&
						Objects.equals(ended.requestId(), query.command().getRequestId().trim())
						&& Objects.equals(ended.commandFingerprint(), submissions.fingerprint(query.command())) &&
						Objects.equals(ended.requestPayload(), submissions.payload(query.command())),
				"结束凭据与原账号、原申请不一致，请保留原文核对");
		return new Outcome(2, "ENDED", null, ended);
	}

	private Scope scope(Query query) {
		var user = SecurityUtils.getUser();
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		require(tenant != null && tenant > 0 && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip()) &&
				!Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				&& user != null && Objects.equals(user.getTenantId(), tenant) && text(user.getUsername()) &&
				text(factory), "请登录原租户并选择实际工厂");
		require(query != null && query.command() != null && text(query.warehouseCode()) &&
						query.warehouseCode().length() <= 64 && !query.warehouseCode().matches(".*[?*].*"),
				"请保留原申请及实际仓库");
		submissions.selections(query.command());
		String warehouse = query.warehouseCode().trim();
		require(permissions.hasPermission("wms_bill_add_in_product") ||
				permissions.hasPermission("wms_bill_add_in_product_" + warehouse), "当前账号无权核对该仓库纠错原请求");
		return new Scope(tenant, factory, user.getUsername(), warehouse);
	}

	static String timestamp(LocalDateTime time) {
		return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(time.truncatedTo(ChronoUnit.MICROS));
	}

	private boolean text(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
