package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.enums.ReportModeEnum;

import java.math.BigDecimal;

/**
 * 执行模板数量配置与新派工实际配额的纯兼容校验。
 *
 * <p>只判断确定的数值存储边界和报工规则矛盾，不读取主数据、不修改模板或派工。
 * 调用方仅在草稿保存、发布和新派工冻结时使用；旧冻结策略及历史报工不重新解释。</p>
 */
final class MesExecutionQuantityCompatibility {

	/** numeric(20,8)最多保留十二位整数；达到该边界无法精确保存。 */
	private static final BigDecimal MAX_SINGLE_EXCLUSIVE = new BigDecimal("1000000000000");

	private MesExecutionQuantityCompatibility() {
	}

	/**
	 * 校验模板数值可原值落库，并保证单件模式至少允许一次报一件。
	 *
	 * @param mode 已按统一枚举解析的报工模式
	 * @param overReportRate 允许超报比例，沿numeric(10,6)，必须在0至1之间且最多六位有效小数
	 * @param maxSingleQuantity 可空的单次上限，单位沿派工物料，沿numeric(20,8)且不得达到一万亿
	 * @throws CheckedException 数量范围、精度或单件模式上限存在确定矛盾时拒绝；尾随零不影响精度
	 */
	static void validateTemplate(ReportModeEnum mode, BigDecimal overReportRate, BigDecimal maxSingleQuantity) {
		if (mode == null) {
			throw new CheckedException("数量兼容校验缺少真实报工模式");
		}
		if (overReportRate == null || overReportRate.signum() < 0
				|| overReportRate.compareTo(BigDecimal.ONE) > 0 || overReportRate.stripTrailingZeros().scale() > 6) {
			throw new CheckedException("允许超报比例须为0至1之间且最多六位小数，不允许落库舍入");
		}
		if (maxSingleQuantity != null && (maxSingleQuantity.signum() <= 0
				|| maxSingleQuantity.compareTo(MAX_SINGLE_EXCLUSIVE) >= 0
				|| maxSingleQuantity.stripTrailingZeros().scale() > 8)) {
			throw new CheckedException("单次报工上限须大于0、小于一万亿且最多八位小数，不允许落库舍入");
		}
		if (exactOne(mode) && maxSingleQuantity != null && maxSingleQuantity.compareTo(BigDecimal.ONE) < 0) {
			throw new CheckedException("一物一码及包装一物一码每次报工一件，单次报工上限不能小于1");
		}
	}

	/**
	 * 在新派工冻结前核对本行真实配额能否按所选数量规则完成。
	 *
	 * @param mode 模板命中或历史兼容策略解析出的统一报工模式
	 * @param partialReportAllowed 是否允许分批；非true沿现有执行合同视为必须一次报完
	 * @param overReportAllowed 是否允许超报；仅true时按原合同将允许超报量计入初始剩余量
	 * @param overReportRate 已选策略的允许超报比例，不修改原值
	 * @param maxSingleQuantity 可空的单次上限，单位沿本行派工物料
	 * @param dispatchQuantity 本行真实派工noticeNum；不能以整单或父工序需求代替
	 * @throws CheckedException 配额非正数、单件配额非整数或一次报完与单次上限冲突时拒绝整笔派工
	 */
	static void validateDispatch(ReportModeEnum mode, Boolean partialReportAllowed, Boolean overReportAllowed,
			BigDecimal overReportRate, BigDecimal maxSingleQuantity, BigDecimal dispatchQuantity) {
		validateTemplate(mode, overReportRate, maxSingleQuantity);
		if (dispatchQuantity == null || dispatchQuantity.signum() <= 0) {
			throw new CheckedException("新派工须提供明确大于0的本行实际配额，不能推断为整单数量");
		}
		if (exactOne(mode)) {
			if (dispatchQuantity.stripTrailingZeros().scale() > 0) {
				throw new CheckedException("一物一码及包装一物一码的本行派工配额必须为正整数");
			}
			if (!Boolean.TRUE.equals(partialReportAllowed) && dispatchQuantity.compareTo(BigDecimal.ONE) != 0) {
				throw new CheckedException("单件模式关闭分批后，本行派工配额只能为1；请允许分批或调整配额");
			}
		}
		// 沿原contract的初始remaining公式，不把允许超报上限改成另一套历史报工语义。
		BigDecimal requiredQuantity = Boolean.TRUE.equals(overReportAllowed)
				? dispatchQuantity.multiply(BigDecimal.ONE.add(overReportRate)) : dispatchQuantity;
		if (!Boolean.TRUE.equals(partialReportAllowed)) {
			if (exactOne(mode) && requiredQuantity.compareTo(BigDecimal.ONE) != 0) {
				throw new CheckedException("单件模式一次报完的实际必报量须为1；请允许分批或关闭超报");
			}
			if (maxSingleQuantity != null && maxSingleQuantity.compareTo(requiredQuantity) < 0) {
				throw new CheckedException("关闭分批后，单次报工上限不能小于含允许超报量的本行一次必报量");
			}
		}
	}

	/**
	 * 核对新配额派工的冻结配置能否同时满足一次报完与原配额硬上限。
	 *
	 * <p>第400节禁止GOOD超过出生配额；正超报率在关闭分批时会使一次必报量超过该硬上限。
	 * 本门只用于新配额及已有配额原件的替代派工，不扩展至无配额历史纠错。</p>
	 *
	 * @param partialReportAllowed 原派工冻结的分批开关
	 * @param overReportAllowed 原派工冻结的超报开关
	 * @param overReportRate 原派工冻结的超报比例，不读取今日模板
	 * @throws CheckedException 正超报率与一次报完、配额硬上限同时生效而不存在合法报告时拒绝
	 */
	static void assertAllocatedDispatch(Boolean partialReportAllowed, Boolean overReportAllowed,
			BigDecimal overReportRate) {
		if (!Boolean.TRUE.equals(partialReportAllowed) && Boolean.TRUE.equals(overReportAllowed)
				&& overReportRate != null && overReportRate.signum() > 0) {
			throw new CheckedException("配额派工不能同时关闭分批并使用正超报比例；请允许分批或关闭超报");
		}
	}

	/** 统一识别现有两个每次只能报一件的对象模式，不为包装或历史别名另造模式。 */
	private static boolean exactOne(ReportModeEnum mode) {
		return mode == ReportModeEnum.ONE_CODE || mode == ReportModeEnum.PACKAGE_ONE_CODE;
	}
}
