package com.kitex.mom.wms.service.organization;

import cn.hutool.core.util.IdUtil;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.PolicyCommand;
import com.kitex.mom.wms.entity.organization.WmsOrganizationTransferPolicy;
import com.kitex.mom.wms.repository.WmsOrganizationTransferRepository;
import com.kitex.mom.wms.service.organization.WmsOrganizationTransferAccess.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

import static com.kitex.mom.wms.service.organization.WmsOrganizationTransferRules.require;

@Service
@RequiredArgsConstructor
public class WmsOrganizationTransferPolicyService {
	private final WmsOrganizationTransferRepository repository;
	private final WmsOrganizationTransferAccess access;

	public WmsOrganizationTransferPolicy current(String warehouse) {
		var scope = access.scope(warehouse, false);
		return repository.policy(scope.tenant(), scope.factory(), scope.warehouse());
	}

	@Transactional(rollbackFor = Exception.class)
	public WmsOrganizationTransferPolicy save(PolicyCommand command) {
		return save(command, false);
	}

	@Transactional(rollbackFor = Exception.class)
	public WmsOrganizationTransferPolicy saveMobile(PolicyCommand command) {
		return save(command, true);
	}

	private WmsOrganizationTransferPolicy save(PolicyCommand command, boolean mobile) {
		require(command != null, "请选择库存组织配置");
		var scope = access.scope(command.getWarehouseCode(), true);
		require(Objects.equals(scope.tenant(), command.getTenantId()) &&
				Objects.equals(scope.userId(), command.getOperatorId()) &&
				Objects.equals(scope.factory(), command.getFactoryCode()), "配置组织或登录身份已变化，请重新准备");
		access.permission(scope, "config");
		if (mobile) {
			access.checkMobile(scope);
		}
		var warehouse = access.warehouse(scope);
		access.configured(scope);
		require(command.getCompanyCode() != null && command.getCompanyCode().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"),
				"公司编码仅支持字母、数字、点、横线和下划线");
		require(command.getCompanyName() != null && !command.getCompanyName().isBlank() &&
				command.getCompanyName().trim().length() <= 200
				&& command.getEnabled() != null && command.getCrossCompany() != null &&
				command.getExpectedVersion() != null, "请填写公司名称、启用状态和当前配置版本");
		repository.lockWarehouse(scope);
		repository.lock(scope.tenant(), "POLICY:" + scope.factory());
		var old = repository.policy(scope.tenant(), scope.factory(), scope.warehouse());
		require(command.getExpectedVersion() == (old == null ? 0 : old.getVersion()),
				"库存组织配置已变化，请刷新后维护");
		for (var row : repository.factoryPolicies(scope.tenant(), scope.factory())) {
			require(Objects.equals(row.getCompanyCode(), command.getCompanyCode()),
					"同一工厂的公司归属必须一致，已有公司绑定不可改写");
		}
		var row = old == null ? new WmsOrganizationTransferPolicy() : old;
		if (old == null) {
			row.setId(IdUtil.getSnowflakeNextId());
			row.setTenantId(scope.tenant());
			row.setFactoryCode(scope.factory());
			row.setWarehouseCode(scope.warehouse());
			row.setCompanyCode(command.getCompanyCode());
		}
		row.setWarehouseName(warehouse.getWarehouseName());
		row.setCompanyName(command.getCompanyName().trim());
		row.setEnabled(command.getEnabled());
		row.setCrossCompany(command.getCrossCompany());
		row.setVersion(command.getExpectedVersion() + 1);
		row.setUpdatedBy(scope.operator());
		row.setUpdatedTime(LocalDateTime.now());
		require((old == null ? repository.insertPolicy(row) : repository.updatePolicy(row,
						command.getExpectedVersion())) == 1,
				"库存组织配置保存失败");
		return row;
	}

	public WmsOrganizationTransferPolicy active(Scope scope) {
		var policy = repository.policy(scope.tenant(), scope.factory(), scope.warehouse());
		require(policy != null && Boolean.TRUE.equals(policy.getEnabled()), "请先配置并启用当前仓库库存组织");
		access.warehouse(scope);
		access.configured(scope);
		return policy;
	}
}
