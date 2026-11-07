package com.kitex.mom.common.barcode.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 条码原定义快照，由业务域与执行实例一并持久保存，后续编码不再查询今日规则。
 *
 * <p>指纹只校验原文完整性，不代替业务域对快照来源、组织和永久请求账的校验。</p>
 */
@Data
@Schema(description = "冻结条码定义")
public class FrozenBarcodeDefinition {

	/** 快照结构版本，仅接受当前公开协议。 */
	@Schema(description = "快照结构版本")
	private String schemaVersion;

	/** 编码算法版本，声明简单字段按顺序以斜杠连接。 */
	@Schema(description = "编码算法版本")
	private String codecVersion;

	/** 来源实名租户ID的十进制原文，禁止前端数值转换。 */
	@Schema(description = "来源租户ID，十进制字符串")
	private String tenantId;

	/** 来源实际工厂编码，执行时必须与当前作用域完全一致。 */
	@Schema(description = "来源实际工厂编码")
	private String factoryCode;

	/** 来源条码规则ID的十进制原文。 */
	@Schema(description = "来源条码规则ID，十进制字符串")
	private String ruleId;

	/** 来源条码规则编码。 */
	@Schema(description = "来源条码规则编码")
	private String code;

	/** 冻结时条码规则名称。 */
	@Schema(description = "冻结时条码规则名称")
	private String name;

	/** 冻结时完整匹配正则；每次编码均按原文校验。 */
	@Schema(description = "冻结时完整匹配正则")
	private String regular;

	/** 冻结时解析插件名称，仅作证据，不调用可变插件参与生成。 */
	@Schema(description = "冻结时解析插件名称")
	private String pluginPath;

	/** 冻结时打印模板编码，可空；不表示模板内容已经冻结。 */
	@Schema(description = "冻结时打印模板编码，可空")
	private String printTemplateCode;

	/** 原样保留调用方永久唯一令牌的完整字段，当前协议为boxCode。 */
	@Schema(description = "原样保留永久唯一令牌的完整字段")
	private String uniqueField;

	/** 原始业务字段，按持久ID升序固定；顺序是本协议的一部分。 */
	@Schema(description = "按持久ID升序冻结的业务字段")
	private List<Field> fields;

	/** 除本字段外全部定义的规范JSON SHA-256，小写十六进制。 */
	@Schema(description = "冻结定义SHA-256原文指纹")
	private String fingerprint;

	/** 一个原始业务字段，不支持模板表达式、截断或转换。 */
	@Data
	@Schema(description = "冻结条码业务字段")
	public static class Field {

		/** 来源业务字段ID的十进制原文，决定冻结编码顺序。 */
		@Schema(description = "来源业务字段ID，十进制字符串")
		private String id;

		/** 冻结时业务字段名称，可空，保留原始定义。 */
		@Schema(description = "冻结时业务字段名称，可空")
		private String name;

		/** 简单业务标识，按该键取得调用方原始字符串值。 */
		@Schema(description = "简单业务字段标识")
		private String value;
	}

}
