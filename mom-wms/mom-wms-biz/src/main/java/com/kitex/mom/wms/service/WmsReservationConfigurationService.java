package com.kitex.mom.wms.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsReservationConfigurationDTO.*;
import com.kitex.mom.wms.entity.WmsReservationMovementPolicyEntity;
import com.kitex.mom.wms.repository.WmsReservationConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WmsReservationConfigurationService {
	private final WmsReservationConfigurationRepository repository;
	private final WmsReservationMovementPolicyService writer;
	private final PermissionService permissions;

	private record Scope(Long tenant, String factory) {
	}

	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Snapshot read() {
		var scope = scope(false);
		repository.lock(scope.tenant(), scope.factory());
		return snapshot(scope);
	}

	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Saved save(Save command) {
		var scope = scope(true);
		require(command != null && command.expectedRevision() != null &&
				command.expectedRevision().matches("[0-9a-f]{64}"), "请先读取当前工厂预留配置");
		var input = normalize(command.policy(), true);
		repository.lock(scope.tenant(), scope.factory());
		var before = snapshot(scope);
		require(before.revision().equals(command.expectedRevision()), "预留配置已更新，请重新读取后核对修改");
		var old = before.policies().stream().filter(p -> Objects.equals(p.id(), input.id())).findFirst();
		require(input.id() == null || old.isPresent() && old.get().movementType().equals(input.movementType()),
				"原策略不存在或作业类型已变化，请重新读取");
		var entity = new WmsReservationMovementPolicyEntity().setMovementType(input.movementType())
				.setReservationAllowed(input.reservationAllowed()).setStatus(input.status()).setRemark(input.remark());
		if (input.id() != null) {
			entity.setId(Long.valueOf(input.id()));
		}
		var written = writer.savePolicy(entity);
		require(written != null && written.getId() != null && written.getId() > 0, "策略保存结果未确认，请重新读取核对");
		String target = written.getId().toString();
		require(input.id() == null || input.id().equals(target), "策略保存标识不一致");
		require(input.id() != null || before.policies().stream().noneMatch(p -> p.id().equals(target)),
				"新增策略错误匹配了既有标识");
		var after = snapshot(scope);
		var expected = new ArrayList<>(before.policies().stream().filter(p -> !p.id().equals(target)).toList());
		expected.add(
				new Policy(target, input.movementType(), input.reservationAllowed(), input.status(), input.remark()));
		expected.sort(Comparator.comparingLong(p -> Long.parseLong(p.id())));
		require(expected.equals(after.policies()), "策略实际保存内容不一致，已回滚；请重新读取核对");
		return new Saved(after, target);
	}

	private Snapshot snapshot(Scope scope) {
		var rows = repository.list(scope.tenant(), scope.factory());
		require(rows != null && rows.size() <= 10000, "预留配置无法完整读取");
		var normalized = rows.stream().map(p -> normalize(p, false))
				.sorted(Comparator.comparingLong(p -> Long.parseLong(p.id()))).toList();
		var ids = new HashSet<String>();
		var active = new HashSet<String>();
		for (var p : normalized) {
			require(ids.add(p.id()) && (!p.status() || active.add(p.movementType())),
					"预留策略标识重复或存在冲突启用项");
		}
		try {
			var canonical = List.of(1, scope.tenant().toString(), scope.factory(), normalized);
			String json = new ObjectMapper().writeValueAsString(canonical);
			String revision = HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
			return new Snapshot(1, scope.tenant().toString(), scope.factory(), revision, normalized);
		} catch (JsonProcessingException | NoSuchAlgorithmException error) {
			throw new IllegalStateException("预留配置无法编码", error);
		}
	}

	private Policy normalize(Policy p, boolean creating) {
		require(p != null && Set.of("produce", "sales").contains(p.movementType() == null ? "" : p.movementType()) &&
				p.reservationAllowed() != null && p.status() != null &&
				(p.remark() == null || p.remark().length() <= 500), "预留策略内容无效");
		String id = p.id();
		require(id == null && creating || id != null && id.matches("[1-9][0-9]{0,18}") &&
				(id.length() < 19 || id.compareTo("9223372036854775807") <= 0), "预留策略标识无效");
		return new Policy(id, p.movementType(), p.reservationAllowed(), p.status(),
				p.remark() == null ? "" : p.remark().trim());
	}

	private Scope scope(boolean writing) {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		var actor = SecurityUtils.getUser();
		require(tenant != null && tenant > 0 && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip()) &&
						!Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && factory != null &&
						!factory.isBlank() &&
						factory.equals(factory.trim()) && factory.length() <= 100 && actor != null &&
						tenant.equals(actor.getTenantId()) && actor.getUsername() != null &&
						!actor.getUsername().isBlank(),
				"请登录实际租户并选择工厂");
		require(permissions.hasPermission("wms_reservationMovementPolicy_view") &&
						(!writing || permissions.hasPermission("wms_reservationMovementPolicy_edit")),
				"当前账号无预留策略查看或维护权限");
		return new Scope(tenant, factory);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
