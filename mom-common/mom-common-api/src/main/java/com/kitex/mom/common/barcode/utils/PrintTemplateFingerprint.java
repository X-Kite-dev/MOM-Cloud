package com.kitex.mom.common.barcode.utils;

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.kitex.mom.common.barcode.entity.PrintTemplateEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Shared layout identity for the business reader and Common renderer; no browser-supplied content. */
public final class PrintTemplateFingerprint {

	private PrintTemplateFingerprint() {
	}

	public static String of(PrintTemplateEntity template) {
		Objects.requireNonNull(template, "Print template is required");
		Map<String, Object> identity = new LinkedHashMap<>();
		identity.put("schemaVersion", "MOM_PRINT_TEMPLATE_V1");
		identity.put("id", text(template.getId()));
		identity.put("tenantId", text(template.getTenantId()));
		identity.put("factoryCode", template.getFactoryCode());
		identity.put("code", template.getCode());
		identity.put("businessTypeCode", template.getBusinessTypeCode());
		identity.put("businessTypeName", template.getBusinessTypeName());
		identity.put("name", template.getName());
		identity.put("content", template.getContent());
		identity.put("jsonData", template.getJsonData());
		identity.put("status", template.getStatus());
		identity.put("delFlag", template.getDelFlag());
		identity.put("updateTime", text(template.getUpdateTime()));
		String json = JSONUtil.toJsonStr(identity, JSONConfig.create().setIgnoreNullValue(false));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}
}
