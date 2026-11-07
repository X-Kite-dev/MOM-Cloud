package com.kitex.mom.wms.service.physical;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsQualityPhysicalDTO.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;

/**
 * 实物V1规范原文与精确数量规则；不得静默修正输入。
 */
public final class WmsPhysicalRules {
	private static final DateTimeFormatter BUSINESS_TIME = new DateTimeFormatterBuilder()
			.appendPattern("uuuu-MM-dd'T'HH:mm:ss")
			.appendFraction(ChronoField.NANO_OF_SECOND, 0, 6, true).toFormatter(Locale.ROOT);

	private WmsPhysicalRules() {
	}

	/**
	 * 断言本域业务条件；不成立时抛出明确业务拒绝。
	 */
	public static void require(boolean ok, String message) {
		if (!ok) {
			throw new CheckedException(message);
		}
	}

	/**
	 * 正十进制ID，仅接受Long范围的规范文本。
	 */
	public static long id(String value) {
		require(value != null && value.matches("[1-9][0-9]{0,18}"), "业务ID必须为规范正整数文本");
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ex) {
			throw new CheckedException("业务ID超过Long范围");
		}
	}

	/**
	 * numeric(24,8)基本单位数量，无指数、尾零、舍入。
	 */
	public static BigDecimal quantity(String value) {
		require(value != null && value.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]*[1-9])?"),
				"数量必须为规范十进制文本");
		BigDecimal n = new BigDecimal(value);
		require(n.signum() > 0 && n.scale() <= 8 && n.precision() - n.scale() <= 16,
				"数量超出numeric(24,8)精度");
		return n;
	}

	/**
	 * 按原基本单位数量输出无尾零文本；缺失历史字段保持null。
	 */
	public static String amount(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	/**
	 * 对外业务时间始终含秒，至多六位小数；历史空值保持null，不改Source.identity原文。
	 */
	public static String time(LocalDateTime value) {
		if (value == null) {
			return null;
		}
		require(value.getNano() % 1000 == 0, "业务时间超出数据库微秒精度");
		return BUSINESS_TIME.format(value);
	}

	/**
	 * 原可空指定条件仅将未指定空串规范为null，不裁剪业务正文。
	 */
	public static String text(String value) {
		return value == null || value.isEmpty() ? null : value;
	}

	/**
	 * 核对必填原文字数及约定ASCII首尾空白；非法输入直接拒绝。
	 */
	public static void field(String value, int max) {
		require(value != null && value.indexOf(0) < 0 && !value.isBlank() &&
						value.codePointCount(0, value.length()) <= max &&
						value.equals(value.replaceAll("^[ \\t\\r\\n]+|[ \\t\\r\\n]+$", "")),
				"原文为空、含NUL、超长或未规范首尾空白");
	}

	/**
	 * 完整集合与父量相等，仓及单位非空；全字段结构比较由record完成。
	 */
	public static void source(Source source) {
		require(source != null && source.v() == 1 && source.key() != null && source.pieces() != null &&
				!source.pieces().isEmpty(), "冻结来源不完整");
		Key k = source.key();
		id(k.tenantId());
		id(k.operationId());
		for (String f : Arrays.asList(k.factoryCode(), k.operationRequestId(), k.sourceType(), k.sourceId(),
				k.holdNo(), k.action(), k.matCode())) {
			field(f, 1000);
		}
		require(Set.of("SCRAP", "RETURN").contains(k.action()), "仅报废和采购退货支持实物执行");
		BigDecimal total = BigDecimal.ZERO;
		long last = 0;
		Set<String> units = new HashSet<>();
		for (Piece p : source.pieces()) {
			require(p != null && id(p.stockId()) > last && "1".equals(p.stockStatus()) && p.identity() != null,
					"片段必须按正ID升序完整唯一");
			require(p.identity().keySet()
					.equals(com.kitex.mom.wms.service.special.WmsSpecialStockProof.identity(
							new com.kitex.mom.wms.entity.WmsLiveStockEntity()).keySet()) &&
					p.identity().values().stream().allMatch(Objects::nonNull), "冻结库存身份键集合不完整");
			last = id(p.stockId());
			field(p.warehouseCode(), 100);
			field(p.unit(), 100);
			units.add(p.unit());
			if (p.physicalOperationId() != null) {
				require(p.physicalOperationId().equals(k.operationId()), "片段所有者不等于原处置");
			}
			total = total.add(quantity(p.quantity()));
		}
		require(units.size() == 1 && total.compareTo(quantity(k.requestedQuantity())) == 0 &&
				total.compareTo(quantity(k.affectedQuantity())) == 0, "完整片段单位或整量不一致");
	}

	/**
	 * 命名空间绑定实名，随机号严格UUID-v4，原文不规范即拒绝。
	 */
	public static void command(Command c) {
		require(c != null && c.v() == 1, "不支持的实物命令版本");
		id(c.operationId());
		id(c.actorId());
		source(c.source());
		require(c.operationId().equals(c.source().key().operationId()) && c.requestId() != null && c.requestId()
						.matches("PX1:" + c.actorId() +
								":[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
				"执行原号或父ID无效");
		field(c.executionRef(), 100);
		field(c.remark(), 1000);
	}
}
