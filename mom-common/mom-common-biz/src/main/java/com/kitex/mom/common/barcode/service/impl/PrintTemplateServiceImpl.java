package com.kitex.mom.common.barcode.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.entity.PrintTemplateEntity;
import com.kitex.mom.common.barcode.mapper.PrintTemplateMapper;
import com.kitex.mom.common.barcode.service.PrintTemplateService;
import com.kitex.mom.common.barcode.template.HtmlTemplate;
import com.kitex.mom.common.barcode.utils.PrintTemplateFingerprint;
import com.kitex.mom.common.barcode.utils.VelocityKit;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 打印模板
 *
 * @author kitex
 * @date 2023-08-17 16:26:53
 */
@Service
public class PrintTemplateServiceImpl extends ServiceImpl<PrintTemplateMapper, PrintTemplateEntity>
		implements PrintTemplateService {

	@Override
	public String preview(String code) {

		PrintTemplateEntity template = getByCode(code);
		if (template == null) {
			throw new CheckedException("模板为空不能预览");
		}

		String html = HtmlTemplate.getHtml();
		String jsonStr = VelocityKit.templateJsonStr(template.getContent(), template.getJsonData());
		return VelocityKit.renderStr(html, jsonStr);
	}

	public PrintTemplateEntity getByCode(String code) {
		return getOne(Wrappers.<PrintTemplateEntity>lambdaQuery().eq(PrintTemplateEntity::getCode, code));
	}

	@Override
	public String billPreview(PreviewObj obj) {
		if (obj == null) {
			throw new CheckedException("打印预览参数不能为空");
		}
		boolean guarded = obj.getExpectedTemplateId() != null || obj.getExpectedTemplateFingerprint() != null
				|| obj.getExpectedBusinessTypeCode() != null;
		PrintTemplateEntity template = guarded ? guardedTemplate(obj) : getByCode(obj.getTemplateCode());
		if (template == null) {
			throw new CheckedException("请先维护模板");
		}
		String html = HtmlTemplate.getHtml();
		String jsonStr = VelocityKit.templateJsonStr(template.getContent(), obj.getJsonData());
		return VelocityKit.renderStr(html, jsonStr);
	}

	/** One scoped lookup is both checked and rendered; never resolve the code a second time after validation. */
	private PrintTemplateEntity guardedTemplate(PreviewObj obj) {
		require(obj.getExpectedTemplateId() != null && obj.getExpectedTemplateId().matches("[1-9][0-9]{0,18}")
				&& obj.getExpectedTemplateFingerprint() != null && obj.getExpectedTemplateFingerprint().matches("[a-f0-9]{64}")
				&& obj.getExpectedBusinessTypeCode() != null && !obj.getExpectedBusinessTypeCode().isBlank()
				&& obj.getTemplateCode() != null && !obj.getTemplateCode().isBlank(), "布局核验三项必须完整，请重新选择打印模板");
		Long id;
		try {
			id = Long.valueOf(obj.getExpectedTemplateId());
		} catch (NumberFormatException invalid) {
			throw new CheckedException("打印模板ID无效");
		}
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		require(tenant != null && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				&& factory != null && !factory.isBlank(), "打印布局核验须指定实际租户与工厂，禁止跳过隔离");
		PrintTemplateEntity template = getOne(new QueryWrapper<PrintTemplateEntity>()
				.eq("id", id).eq("code", obj.getTemplateCode()).eq("tenant_id", tenant).eq("factory_code", factory)
				.eq("status", true).eq("del_flag", "0").eq("business_type_code", obj.getExpectedBusinessTypeCode()));
		require(template != null && Objects.equals(id, template.getId()) && Objects.equals(tenant, template.getTenantId())
				&& Objects.equals(factory, template.getFactoryCode()) && Objects.equals(obj.getTemplateCode(), template.getCode())
				&& Boolean.TRUE.equals(template.getStatus()) && "0".equals(template.getDelFlag())
				&& Objects.equals(obj.getExpectedBusinessTypeCode(), template.getBusinessTypeCode()), "打印布局不存在、已停用或不属于当前业务和组织");
		require(Objects.equals(obj.getExpectedTemplateFingerprint(), PrintTemplateFingerprint.of(template)), "打印布局已变化，请重新读取并确认");
		return template;
	}

	private static void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}

}
