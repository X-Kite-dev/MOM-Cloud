package com.kitex.mom.wms.service.special;

import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.*;
import com.kitex.mom.wms.service.special.WmsSpecialAccess.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.Comparator;

import static com.kitex.mom.wms.service.special.WmsSpecialRules.*;

@Service
@RequiredArgsConstructor
public class WmsSpecialPolicyService {
	private final WmsSpecialRepository repository;
	private final WmsSpecialAccess access;

	public PolicySnapshot current(Scope scope) {
		var value = repository.policy(scope);
		checkRules(value.rules());
		return value;
	}

	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public PolicySnapshot read(String warehouse) {
		var scope = access.scope(warehouse, false);
		repository.lockWarehouse(scope);
		return current(scope);
	}

	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public PolicySnapshot save(PolicySave command) {
		require(command != null, "请先读取仓库规则");
		var scope = access.scope(command.warehouseCode(), true);
		access.requireAction(scope, "config");
		access.warehouse(scope);
		checkRules(command.rules());
		require(command.expectedVersion() != null &&
				("0".equals(command.expectedVersion()) || id(command.expectedVersion()) > 0), "规则版本无效");
		repository.lockWarehouse(scope);
		var before = current(scope);
		require(before.version().equals(command.expectedVersion()), "规则已变化，请重新读取后核对修改");
		var rules = command.rules().stream().sorted(Comparator.comparing(Rule::kind)).toList();
		var after = repository.savePolicy(scope, before.version(), rules);
		require(after.rules().equals(rules) && id(after.version()) == Long.parseLong(before.version()) + 1,
				"规则实际保存内容或版本未确认，已回滚");
		return after;
	}
}
