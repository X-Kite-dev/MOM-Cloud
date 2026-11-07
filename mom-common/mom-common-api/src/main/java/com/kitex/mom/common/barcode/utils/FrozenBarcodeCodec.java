package com.kitex.mom.common.barcode.utils;

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.barcode.dto.FrozenBarcodeDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 冻结条码纯编码：只使用已保存原定义和调用方原值，不查库、不取当前时间、不消耗序列。
 *
 * <p>调用方须先持久化唯一令牌及完整命令；本工具不承诺跨不同定义的全局无碰撞，
 * 最终业务箱码仍须由业务域数据库唯一约束保护。快照必须来自可信持久执行依据。</p>
 */
public final class FrozenBarcodeCodec {

	/** 冻结定义结构版本，与业务域持久快照共同保留。 */
	public static final String SCHEMA_VERSION = "MOM_FROZEN_BARCODE_DEFINITION_V1";

	/** 原始字符串字段按固定顺序以斜杠连接，不执行模板表达式。 */
	public static final String CODEC_VERSION = "SLASH_FIELDS_V1";

	/** 必须完整保留业务域永久唯一令牌的协议字段。 */
	public static final String UNIQUE_FIELD = "boxCode";

	private static final Pattern FIELD_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]{0,18}");

	private FrozenBarcodeCodec() {
	}

	/**
	 * 按可信执行快照编码，唯一字段完整保留调用方永久请求令牌。
	 * @param definition 业务域从持久执行依据读出的原定义
	 * @param values 各业务字段原始字符串，未使用的附加业务值不参与编码
	 * @param tenantId 当前已经核验的实际租户ID
	 * @param factoryCode 当前已经核验的实际工厂编码
	 * @return 通过原正则完整匹配的条码；缺值、跨组织或定义变化时拒绝
	 */
	public static String render(FrozenBarcodeDefinition definition, Map<String, String> values, Long tenantId,
			String factoryCode) {
		validate(definition, tenantId, factoryCode);
		require(values != null, "冻结条码业务值不能为空");
		List<String> segments = new ArrayList<>();
		for (FrozenBarcodeDefinition.Field field : definition.getFields()) {
			String value = values.get(field.getValue());
			require(value != null && !value.isBlank(), "冻结条码业务字段缺值：" + field.getValue());
			require(value.indexOf('/') < 0 && value.chars().noneMatch(Character::isISOControl),
					"条码业务值不能包含分段符或控制字符：" + field.getValue());
			if (UNIQUE_FIELD.equals(field.getValue())) {
				positiveId(value, "条码唯一字段必须是已持久化的正十进制令牌");
			}
			// 不trim、不截断、不转换，保证原唯一令牌完整构成一个独立分段。
			segments.add(value);
		}
		String barcode = String.join("/", segments);
		require(regular(definition.getRegular()).matcher(barcode).matches(),
				"生成内容不符合冻结条码正则，请维护兼容规则；不能截断或转换唯一令牌");
		return barcode;
	}

	/**
	 * 校验快照结构、确定顺序、组织及指纹，不查询当前主数据。
	 * @param definition 从可信执行依据读取的完整快照
	 * @param tenantId 当前已经核验的实际租户ID
	 * @param factoryCode 当前已经核验的实际工厂编码
	 */
	public static void validate(FrozenBarcodeDefinition definition, Long tenantId, String factoryCode) {
		require(definition != null, "缺少冻结条码定义");
		require(tenantId != null && tenantId > 0 && factoryCode != null && !factoryCode.isBlank(),
				"冻结条码必须指定已核验的实际租户和工厂");
		require(Objects.equals(tenantId.toString(), definition.getTenantId())
				&& Objects.equals(factoryCode, definition.getFactoryCode()), "冻结条码定义不属于当前实际组织");
		require(SCHEMA_VERSION.equals(definition.getSchemaVersion()) && CODEC_VERSION.equals(definition.getCodecVersion()),
				"冻结条码协议版本不受支持");
		positiveId(definition.getRuleId(), "冻结条码原规则ID无效");
		require(definition.getCode() != null && !definition.getCode().isBlank(), "冻结条码原规则编码不能为空");
		require(UNIQUE_FIELD.equals(definition.getUniqueField()), "冻结条码未声明受支持的完整唯一字段");
		regular(definition.getRegular());
		validateFields(definition.getFields());
		require(definition.getFingerprint() != null && definition.getFingerprint().matches("[a-f0-9]{64}")
				&& Objects.equals(definition.getFingerprint(), fingerprint(definition)), "冻结条码原文指纹不一致");
	}

	/**
	 * 生成完整定义的规范JSON指纹，本身不签名、不证明快照来源可信。
	 * @param definition 已按原持久字段顺序组装的完整定义
	 * @return 除fingerprint以外全部协议字段的SHA-256
	 */
	public static String fingerprint(FrozenBarcodeDefinition definition) {
		require(definition != null && definition.getFields() != null, "冻结条码定义及业务字段不能为空");
		Map<String, Object> identity = new LinkedHashMap<>();
		identity.put("schemaVersion", definition.getSchemaVersion());
		identity.put("codecVersion", definition.getCodecVersion());
		identity.put("tenantId", definition.getTenantId());
		identity.put("factoryCode", definition.getFactoryCode());
		identity.put("ruleId", definition.getRuleId());
		identity.put("code", definition.getCode());
		identity.put("name", definition.getName());
		identity.put("regular", definition.getRegular());
		identity.put("pluginPath", definition.getPluginPath());
		identity.put("printTemplateCode", definition.getPrintTemplateCode());
		identity.put("uniqueField", definition.getUniqueField());
		List<Map<String, Object>> fields = new ArrayList<>();
		for (FrozenBarcodeDefinition.Field field : definition.getFields()) {
			require(field != null, "冻结条码业务字段不能为空项");
			Map<String, Object> source = new LinkedHashMap<>();
			source.put("id", field.getId());
			source.put("name", field.getName());
			source.put("value", field.getValue());
			fields.add(source);
		}
		identity.put("fields", fields);
		String json = JSONUtil.toJsonStr(identity, JSONConfig.create().setIgnoreNullValue(false));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("当前运行环境不支持SHA-256", impossible);
		}
	}

	/** 持久ID顺序和字段名均须唯一，避免运行时重新猜测顺序或执行模板表达式。 */
	private static void validateFields(List<FrozenBarcodeDefinition.Field> fields) {
		require(fields != null && !fields.isEmpty(), "冻结条码业务字段不能为空");
		Set<String> names = new HashSet<>();
		long previousId = 0;
		for (FrozenBarcodeDefinition.Field field : fields) {
			require(field != null, "冻结条码业务字段不能为空项");
			long id = positiveId(field.getId(), "冻结条码业务字段ID无效");
			require(id > previousId, "冻结条码业务字段必须按原持久ID唯一升序保存");
			previousId = id;
			require(field.getValue() != null && FIELD_IDENTIFIER.matcher(field.getValue()).matches(),
					"冻结条码只支持简单业务标识，不支持模板表达式、截断或转换");
			require(names.add(field.getValue()), "冻结条码业务标识重复：" + field.getValue());
		}
		require(names.contains(UNIQUE_FIELD), "条码规则必须包含完整boxCode业务字段，才能承接永久唯一令牌");
	}

	private static long positiveId(String value, String message) {
		require(value != null && POSITIVE_ID.matcher(value).matches(), message);
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException invalid) {
			throw new CheckedException(message, invalid);
		}
	}

	private static Pattern regular(String value) {
		require(value != null && !value.isBlank(), "冻结条码完整匹配正则不能为空");
		try {
			return Pattern.compile(value);
		}
		catch (PatternSyntaxException invalid) {
			throw new CheckedException("冻结条码正则无效", invalid);
		}
	}

	private static void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}

}
