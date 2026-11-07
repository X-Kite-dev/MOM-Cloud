package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmClassesEntity;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 一条正式班次规则在明确所属日期上的不可变实例。
 *
 * <p>只计算规则与日期，不查询远端或授予组织权限；调用方仍须核实规则来源、实际组织和操作权限。
 * 实例编码沿用 yyMMdd 加原规则编码，完整日期与跨夜窗口独立保存，禁止从编码倒推日期。</p>
 *
 * @param tenantId    规则所属租户的精确字符串身份
 * @param factoryCode 规则所属实际工厂编码
 * @param ruleId      原 MDM 班次规则的精确字符串身份
 * @param ruleCode    原 MDM 班次模板编码，可包含数字
 * @param shiftCode   yyMMdd 加原模板编码的派工班次实例编码
 * @param shiftName   原规则的班次名称快照
 * @param shiftDate   班次开始日，跨夜班仍属于此日期
 * @param windowStart 所属日期与规则开始时间组成的班次开始时刻
 * @param windowEnd   班次结束时刻；跨夜规则顺延至次日，窗口为左闭右开
 */
public record MdmShiftOccurrence(String tenantId, String factoryCode, String ruleId, String ruleCode,
                                 String shiftCode, String shiftName, LocalDate shiftDate, LocalDateTime windowStart,
                                 LocalDateTime windowEnd) {

	/**
	 * 正式日期班次实例的快照合同；调用方应与实例一起冻结。
	 */
	public static final String CONTRACT = "MDM_DATED_SHIFT_V1";

	private static final DateTimeFormatter DATE_CODE = DateTimeFormatter.ofPattern("yyMMdd");

	/**
	 * 校验完整实例，防止反序列化的快照夹带另一编码、所属日期或不合法窗口。
	 *
	 * @throws IllegalArgumentException 身份、编码或日期窗口不完整、不一致时拒绝
	 */
	public MdmShiftOccurrence {
		positiveId(tenantId, "班次租户身份");
		positiveId(ruleId, "班次规则身份");
		text(factoryCode, "班次工厂编码");
		text(ruleCode, "班次模板编码");
		text(shiftCode, "班次实例编码");
		// 名称只是展示原文，不参与编码拆分；合法名称中的逗号等标点不能使整厂班次失效。
		require(shiftName != null && !shiftName.isBlank() && shiftName.length() <= 100, "班次名称缺失或超长");
		require(shiftDate != null && windowStart != null && windowEnd != null, "班次所属日期及完整窗口不能为空");
		require(shiftCode.equals(DATE_CODE.format(shiftDate) + ruleCode), "班次实例编码与原规则及所属日期不一致");
		require(windowStart.toLocalDate().equals(shiftDate) && windowEnd.isAfter(windowStart)
						&& Duration.between(windowStart, windowEnd).compareTo(Duration.ofDays(1)) < 0,
				"班次窗口必须从所属日开始且短于24小时，跨夜结束应顺延一天");
	}

	/**
	 * 将启用的完整正式规则与显式所属日期合成为实例；结束时间早于开始时间时顺延一天。
	 *
	 * @param rule      调用方已取得的原租户、工厂班次规则，不能以名称或空来源补造
	 * @param shiftDate 明确选择的班次开始日期
	 * @return 含完整组织、规则身份、日期和窗口的实例
	 * @throws IllegalArgumentException 规则停用、身份/时间/编码缺失或零长度时拒绝
	 */
	public static MdmShiftOccurrence from(MdmClassesEntity rule, LocalDate shiftDate) {
		require(rule != null && Boolean.TRUE.equals(rule.getStatus()), "正式班次规则不存在或已停用");
		require(rule.getTenantId() != null && rule.getId() != null && shiftDate != null,
				"正式班次租户、规则身份和所属日期不能为空");
		require(rule.getStartTime() != null && rule.getEndTime() != null
				&& !Objects.equals(rule.getStartTime(), rule.getEndTime()), "正式班次时间缺失或窗口为零");
		LocalDateTime start = shiftDate.atTime(rule.getStartTime());
		LocalDateTime end = shiftDate.atTime(rule.getEndTime());
		if (end.isBefore(start)) {
			end = end.plusDays(1);
		}
		return new MdmShiftOccurrence(rule.getTenantId().toString(), rule.getFactoryCode(), rule.getId().toString(),
				rule.getShiftCode(), DATE_CODE.format(shiftDate) + rule.getShiftCode(), rule.getShiftName(),
				shiftDate, start, end);
	}

	/**
	 * 校验字符串身份仍在数据库 Long 正整数范围内，避免跨 JavaScript 边界丢失精度。
	 */
	private static void positiveId(String value, String label) {
		require(value != null && value.matches("[1-9][0-9]{0,18}"), label + "无效");
		try {
			Long.parseLong(value);
		} catch (NumberFormatException failure) {
			throw new IllegalArgumentException(label + "超出Long范围", failure);
		}
	}

	/**
	 * 编码按原文保存，不修剪、不删数字或自动补来源；名称另按主数据文本合同验证。
	 */
	private static void text(String value, String label) {
		require(value != null && !value.isBlank() && value.equals(value.trim()) && value.length() <= 100
						&& value.indexOf(',') < 0 && value.chars().noneMatch(Character::isISOControl),
				label + "缺失或格式无效");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException(message);
		}
	}
}
