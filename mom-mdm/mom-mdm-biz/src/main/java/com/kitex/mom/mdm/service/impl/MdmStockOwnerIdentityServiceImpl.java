package com.kitex.mom.mdm.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmStockOwnerIdentityDTO;
import com.kitex.mom.mdm.dto.MdmStockOwnerLookupDTO;
import com.kitex.mom.mdm.dto.MdmStockOwnerSearchDTO;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;
import com.kitex.mom.mdm.enums.FactoryModelPermissionEnum;
import com.kitex.mom.mdm.enums.FactoryModelTypeEnum;
import com.kitex.mom.mdm.mapper.MdmStockOwnerIdentityMapper;
import com.kitex.mom.mdm.service.MdmFactoryModelService;
import com.kitex.mom.mdm.service.MdmStockOwnerIdentityService;
import com.kitex.mom.mdm.service.MdmUserFactoryModelBindService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 依当前实名租户、工厂和MDM真实人员绑定提供最小货主身份，不信任客户端组织、角色或资格。
 */
@Service
@RequiredArgsConstructor
public class MdmStockOwnerIdentityServiceImpl implements MdmStockOwnerIdentityService {
	private final MdmFactoryModelService factories;
	private final MdmUserFactoryModelBindService bindings;
	private final MdmStockOwnerIdentityMapper mapper;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public MdmStockOwnerIdentityDTO resolve(MdmStockOwnerLookupDTO query) {
		Scope scope = scope();
		require(query != null, "货主查询不能为空");
		String type = type(query.getOwnerType());
		String code = query.getOwnerCode();
		require(code != null && !code.isBlank() && code.equals(code.trim()) && code.length() <= 64,
				"货主编码必须为无首尾空白的1至64字符原文");
		if (query.getExpectedOwnerId() != null) {
			positiveId(query.getExpectedOwnerId(), "预期货主主键");
		}
		// 主键不得加入SQL过滤，以免错误主键掩盖本组织同编码多条的真实歧义。
		List<MdmStockOwnerIdentityDTO> rows = mapper.resolve(scope.tenant(), scope.factory(), type, code);
		require(rows != null && rows.size() == 1, "货主不存在或编码不唯一，请核对当前工厂主数据");
		MdmStockOwnerIdentityDTO identity = identity(rows.get(0), scope, type);
		require(Objects.equals(code, identity.getCode()), "货主精确编码证据不一致");
		require(query.getExpectedOwnerId() == null || Objects.equals(query.getExpectedOwnerId(), identity.getId()),
				"货主主键与当前编码对应的真实身份不一致");
		return identity;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<MdmStockOwnerIdentityDTO> search(MdmStockOwnerSearchDTO query) {
		Scope scope = scope();
		require(query != null, "货主搜索不能为空");
		String type = type(query.getOwnerType());
		String keyword = query.getKeyword() == null ? null : query.getKeyword().trim();
		require(keyword != null && !keyword.isBlank() && keyword.length() <= 100,
				"货主搜索关键词修剪后须为1至100字符");
		List<MdmStockOwnerIdentityDTO> rows = mapper.search(scope.tenant(), scope.factory(), type, keyword);
		require(rows != null && rows.size() <= 20, "货主搜索结果无效");
		for (MdmStockOwnerIdentityDTO row : rows) {
			identity(row, scope, type);
		}
		return rows;
	}

	/**
	 * 逐次通过本地真实工厂及人员绑定服务核本人读权，禁止使用内部免令牌远程或角色字符串代替。
	 * 工厂节点没有factoryCode列，以tenant+唯一启用FACTORY编码定位；绑定再显式限定实际工厂。
	 */
	private Scope scope() {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		var user = SecurityUtils.getUser();
		require(tenant != null && tenant > 0 && user != null && user.getId() != null && user.getId() > 0
						&& Objects.equals(user.getTenantId(), tenant)
						&& !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
						&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
						&& factory != null && !factory.isBlank() && factory.equals(factory.trim()),
				"请实名登录实际租户并选择已授权工厂");
		var nodes = factories.list(Wrappers.<MdmFactoryModelEntity>lambdaQuery()
				.select(MdmFactoryModelEntity::getId)
				.eq(MdmFactoryModelEntity::getTenantId, tenant)
				.eq(MdmFactoryModelEntity::getDelFlag, "0")
				.eq(MdmFactoryModelEntity::getCode, factory)
				.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.FACTORY.getCode())
				.eq(MdmFactoryModelEntity::getStatus, Boolean.TRUE).last("LIMIT 2"));
		require(nodes != null && nodes.size() == 1 && nodes.get(0).getId() != null,
				"当前工厂不是唯一有效的实际工厂");
		var granted = bindings.list(Wrappers.<MdmUserFactoryModelBindEntity>lambdaQuery()
				.select(MdmUserFactoryModelBindEntity::getPermissionType)
				.eq(MdmUserFactoryModelBindEntity::getTenantId, tenant)
				.eq(MdmUserFactoryModelBindEntity::getFactoryCode, factory)
				.eq(MdmUserFactoryModelBindEntity::getDelFlag, "0")
				.eq(MdmUserFactoryModelBindEntity::getUserId, user.getId())
				.eq(MdmUserFactoryModelBindEntity::getFactoryModelId, nodes.get(0).getId())
				.eq(MdmUserFactoryModelBindEntity::getStatus, Boolean.TRUE));
		require(granted != null && granted.stream().anyMatch(binding -> {
			String value = binding.getPermissionType();
			FactoryModelPermissionEnum permission = value == null ? null
					: FactoryModelPermissionEnum.getByCode(value.trim().toLowerCase(Locale.ROOT));
			return permission != null && permission.hasPermission(FactoryModelPermissionEnum.READ);
		}), "当前人员没有实际工厂读取权限");
		return new Scope(tenant, factory);
	}

	/**
	 * 复核最小投影的真实组织与文本身份，再按原状态计算资格；不修剪或替换原货主信息。
	 */
	private MdmStockOwnerIdentityDTO identity(MdmStockOwnerIdentityDTO row, Scope scope, String type) {
		require(row != null, "货主身份结果缺失");
		positiveId(row.getId(), "真实货主主键");
		positiveId(row.getTenantId(), "真实货主租户");
		require(Objects.equals(row.getTenantId(), scope.tenant().toString())
				&& Objects.equals(row.getFactoryCode(), scope.factory())
				&& Objects.equals(row.getOwnerType(), type)
				&& row.getCode() != null && !row.getCode().isBlank(), "货主组织或身份结果不一致");
		if ("CUSTOMER_SUPPLIED".equals(type)) {
			require(row.getSupplierCheckStatus() == null, "客户不能带入供应商审核状态");
			row.setEligible(Boolean.TRUE.equals(row.getEnabled()));
		} else {
			row.setEligible(Boolean.TRUE.equals(row.getEnabled()) && "1".equals(row.getSupplierCheckStatus()));
		}
		return row;
	}

	/**
	 * 校验货权类型的精确原文，不把未知或空类型改成自有或其他主体。
	 */
	private String type(String value) {
		require("CUSTOMER_SUPPLIED".equals(value) || "CONSIGNED".equals(value), "仅支持客供或寄售货主查询");
		return value;
	}

	/**
	 * 校验规范正int64文本，拒绝前导零、科学记数、小数、空白或溢出。
	 */
	private void positiveId(String value, String label) {
		require(value != null && value.matches("[1-9][0-9]{0,18}")
						&& (value.length() < 19 || value.compareTo("9223372036854775807") <= 0),
				label + "必须为规范正int64文本");
	}

	/**
	 * 将缺失资格或证据显式作为业务拒绝，查询失败不伪装成空候选。
	 */
	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	/**
	 * 当前实名与真实工厂读权已核对的内部范围，不接收正文组织。
	 */
	private record Scope(Long tenant, String factory) {
	}
}
