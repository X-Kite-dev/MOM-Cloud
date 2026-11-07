package com.kitex.mom.common.barcode.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.barcode.dto.FrozenBarcodeDefinition;
import com.kitex.mom.common.barcode.dto.req.BarcodeDefinitionFreezeReq;
import com.kitex.mom.common.barcode.entity.BarcodeBusinessFieldEntity;
import com.kitex.mom.common.barcode.entity.BarcodeRuleEntity;
import com.kitex.mom.common.barcode.mapper.BarcodeRuleMapper;
import com.kitex.mom.common.barcode.service.BarcodeBusinessFieldService;
import com.kitex.mom.common.barcode.service.FrozenBarcodeDefinitionService;
import com.kitex.mom.common.barcode.utils.FrozenBarcodeCodec;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 实名核工厂授权后读取一致规则快照；锁住父规则直至子字段读取完成。
 *
 * <p>当前条码规则没有启用状态或修订号，不能伪造“已启用版本”；返回本次真实原文及指纹。</p>
 */
@Service
@RequiredArgsConstructor
public class FrozenBarcodeDefinitionServiceImpl implements FrozenBarcodeDefinitionService {

	private final BarcodeRuleMapper rules;

	private final BarcodeBusinessFieldService businessFields;

	private final RemoteMdmFactoryAccessService factoryAccess;

	/** {@inheritDoc} */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public FrozenBarcodeDefinition freezeDefinition(BarcodeDefinitionFreezeReq request) {
		require(request != null && request.getBarcodeRuleCode() != null && !request.getBarcodeRuleCode().isBlank(),
				"条码规则编码不能为空");
		Scope scope = requireScope();
		// 更新接口先更新父规则、再替换字段，二者在同一事务中；先锁父规则避免读取中间定义。
		List<BarcodeRuleEntity> matches = rules.selectList(new QueryWrapper<BarcodeRuleEntity>()
				.eq("tenant_id", scope.tenantId()).eq("factory_code", scope.factoryCode())
				.eq("code", request.getBarcodeRuleCode()).eq("del_flag", "0").last("FOR UPDATE"));
		require(matches != null && matches.size() == 1, "当前实际组织内条码规则不存在或编码不唯一");
		BarcodeRuleEntity rule = matches.get(0);
		require(rule.getId() != null && Objects.equals(scope.tenantId(), rule.getTenantId())
				&& Objects.equals(scope.factoryCode(), rule.getFactoryCode()) && "0".equals(rule.getDelFlag()),
				"条码原规则组织或有效身份无法核验");
		List<BarcodeBusinessFieldEntity> fields = businessFields.list(new QueryWrapper<BarcodeBusinessFieldEntity>()
				.eq("barcode_rule_id", rule.getId()).eq("tenant_id", scope.tenantId())
				.eq("factory_code", scope.factoryCode()).eq("del_flag", "0").orderByAsc("id"));
		FrozenBarcodeDefinition definition = snapshot(rule, fields, scope);
		definition.setFingerprint(FrozenBarcodeCodec.fingerprint(definition));
		FrozenBarcodeCodec.validate(definition, scope.tenantId(), scope.factoryCode());
		return definition;
	}

	/** 工厂头只定位当前范围，实际读权限仍须由MDM按实名用户核验。 */
	private Scope requireScope() {
		var authentication = SecurityUtils.getAuthentication();
		var user = SecurityUtils.getUser();
		Long tenantId = TenantContextHolder.getTenantId();
		String factoryCode = FactoryContextHolder.getFactoryCode();
		require(authentication != null && authentication.isAuthenticated() && user != null
				&& user.getId() != null && user.getId() > 0 && tenantId != null && tenantId > 0
				&& Objects.equals(tenantId, user.getTenantId()) && !TenantContextHolder.getTenantSkip()
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				&& factoryCode != null && !factoryCode.isBlank(), "条码定义冻结须实名登录并选择实际租户和工厂，禁止跳过隔离");
		R<Boolean> authorization;
		try {
			authorization = factoryAccess.check(new MdmFactoryAccessCheck().setUserId(user.getId())
					.setFactoryCode(factoryCode).setRequiredPermission("read"));
		}
		catch (RuntimeException unavailable) {
			throw new CheckedException("条码定义工厂访问授权暂时无法核验，请稍后重试", unavailable);
		}
		require(authorization != null && authorization.isOk() && Boolean.TRUE.equals(authorization.getData()),
				"当前用户没有所需工厂读取权限，或授权结果不可核验");
		return new Scope(tenantId, factoryCode);
	}

	/** 保存真实原字段，不补企业格式、不执行旧模板表达式。 */
	private FrozenBarcodeDefinition snapshot(BarcodeRuleEntity rule, List<BarcodeBusinessFieldEntity> fields,
			Scope scope) {
		FrozenBarcodeDefinition result = new FrozenBarcodeDefinition();
		result.setSchemaVersion(FrozenBarcodeCodec.SCHEMA_VERSION);
		result.setCodecVersion(FrozenBarcodeCodec.CODEC_VERSION);
		result.setTenantId(scope.tenantId().toString());
		result.setFactoryCode(scope.factoryCode());
		result.setRuleId(rule.getId().toString());
		result.setCode(rule.getCode());
		result.setName(rule.getName());
		result.setRegular(rule.getRegular());
		result.setPluginPath(rule.getPluginPath());
		result.setPrintTemplateCode(rule.getPrintTemplateCode());
		result.setUniqueField(FrozenBarcodeCodec.UNIQUE_FIELD);
		List<FrozenBarcodeDefinition.Field> frozenFields = new ArrayList<>();
		require(fields != null, "条码原业务字段无法读取");
		for (BarcodeBusinessFieldEntity field : fields) {
			require(field != null && field.getId() != null && Objects.equals(rule.getId(), field.getBarcodeRuleId())
					&& Objects.equals(scope.tenantId(), field.getTenantId())
					&& Objects.equals(scope.factoryCode(), field.getFactoryCode()) && "0".equals(field.getDelFlag()),
					"条码原业务字段组织或身份无法核验");
			FrozenBarcodeDefinition.Field frozen = new FrozenBarcodeDefinition.Field();
			frozen.setId(field.getId().toString());
			frozen.setName(field.getName());
			frozen.setValue(field.getValue());
			frozenFields.add(frozen);
		}
		result.setFields(frozenFields);
		return result;
	}

	private static void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}

	/** 已经核验身份和访问权限的本次读取范围，不接受请求体替换。 */
	private record Scope(Long tenantId, String factoryCode) {
	}

}
