package com.kitex.mom.wms.service.rework;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsNcrReworkDTO.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * NCR原位返工V1输入边界；永久命令必须已经规范，不能在重放时裁剪或重写原文。
 */
public final class WmsNcrReworkRules {
	private WmsNcrReworkRules() {
	}

	/**
	 * 正整数身份以原文传输，拒绝溢出、前导零和跨协议表示。
	 */
	public static long id(String value) {
		require(value != null && value.matches("[1-9][0-9]{0,18}"), "返工身份须为正整数文本");
		try {
			long result = Long.parseLong(value);
			require(result > 0, "返工身份必须大于零");
			return result;
		} catch (NumberFormatException e) {
			throw new CheckedException("返工身份超出整数范围");
		}
	}

	/**
	 * 量限numeric(24,8)，禁止指数、负数及无法精确回读的文本。
	 */
	public static BigDecimal quantity(String value) {
		require(value != null && value.matches("[0-9]{1,24}(\\.[0-9]{1,8})?"), "返工数量格式或精度无效");
		BigDecimal n = new BigDecimal(value);
		require(n.signum() > 0 && n.precision() - n.scale() <= 16, "返工数量必须为正且最多16位整数8位小数");
		return n;
	}

	/**
	 * 原件预检只规范数量与可空单位一次，永久命令必须使用回传值。
	 */
	public static Key key(Key k, boolean inspect) {
		require(k != null, "返工原案不能为空");
		id(k.tenantId());
		id(k.ncrId());
		id(k.detailId());
		id(k.proposalId());
		id(k.rootHoldOperationId());
		id(k.parentOperationId());
		field(k.factoryCode(), 100, true);
		field(k.detailNo(), 100, true);
		field(k.holdNo(), 100, true);
		String q = quantity(k.quantity()).stripTrailingZeros().toPlainString();
		field(k.unit(), 100, !inspect);
		if (!inspect) {
			require(q.equals(k.quantity()), "永久返工数量须使用预检规范文本");
		}
		if (k.binding() != null) {
			require(k.binding().target() != null &&
					Objects.equals(k.rootHoldOperationId(), k.binding().rootHoldOperationId())
					&& k.binding().reholdOperationId() == null, "特殊返工绑定必须为原root，不得复用重隔离字段");
		}
		return new Key(k.tenantId(), k.factoryCode(), k.ncrId(), k.detailId(), k.detailNo(), k.proposalId(),
				k.rootHoldOperationId(), k.parentOperationId(), k.holdNo(), q,
				k.unit() == null || k.unit().isBlank() ? null : k.unit(), k.binding());
	}

	/**
	 * 冻结源必须有序、无重复、同仓同量，并保留完整文本身份。
	 */
	public static void source(Source s) {
		require(s != null && s.version() == 1, "不支持的返工来源版本");
		key(s.key(), false);
		field(s.warehouseCode(), 100, true);
		require(s.pieces() != null && !s.pieces().isEmpty() && s.pieces().size() <= 10000,
				"返工原件集合缺失或超限");
		long previous = 0;
		BigDecimal sum = BigDecimal.ZERO;
		for (Piece p : s.pieces()) {
			require(p != null, "返工原件不完整");
			long current = id(p.stockId());
			require(current > previous, "返工原件必须按库存ID数值升序且不能重复");
			previous = current;
			BigDecimal n = quantity(p.quantity());
			require(n.stripTrailingZeros().toPlainString().equals(p.quantity()), "原件数量须为规范文本");
			sum = sum.add(n);
			require(Objects.equals(s.warehouseCode(), p.warehouseCode()), "返工原件不能跨仓");
			field(p.siteCode(), 100, false);
			field(p.containerCode(), 100, false);
			require(p.identity() != null && p.identity().keySet()
							.equals(com.kitex.mom.wms.service.special.WmsSpecialStockProof.identity(
									new com.kitex.mom.wms.entity.WmsLiveStockEntity()).keySet()),
					"返工原件完整身份缺失");
			for (var e : p.identity().entrySet()) {
				field(e.getKey(), 100, true);
				// 原实物文本逐字保留，不能裁剪历史名称、凭据或来源快照。
				require(e.getValue() == null ||
								e.getValue().length() <= 200000 && e.getValue().indexOf('\0') < 0,
						"返工原件身份字段超长或含非法字符");
				require(e.getValue() != null, "原件空值必须使用原证明的空串规则");
			}
			require(Objects.equals(s.key().unit(), p.identity().get("matUnit")), "冻结源基本单位不一致");
		}
		require(sum.compareTo(quantity(s.key().quantity())) == 0, "返工原件合计不等于分类整量");
	}

	/**
	 * 验证独立复核、完成三字段原子可空和原号格式；缺完成仅允许REJECT。
	 */
	public static void command(Command c) {
		command(c, true);
	}

	/**
	 * 只读查询允许有权查看者与发布/完成人相同，独立性仅约束新永久决定。
	 */
	public static void command(Command c, boolean independent) {
		require(c != null && c.version() == 1, "不支持的返工命令版本");
		request(c.requestId());
		source(c.source());
		actor(c.reviewer());
		id(c.deciderId());
		require(Set.of("PASS", "REJECT").contains(c.decision()), "返工结论只能为PASS或REJECT");
		field(c.reviewEvidence(), 2000, true);
		boolean completion = c.completionId() != null;
		require(completion == (c.completionRequestId() != null) && completion == (c.executor() != null),
				"完成ID、原号和实际完成者必须同时有或同时为空");
		require(completion || "REJECT".equals(c.decision()), "通过返工复核必须具有有效完成事实");
		if (completion) {
			id(c.completionId());
			request(c.completionRequestId());
			actor(c.executor());
			require(!independent || !c.reviewer().userId().equals(c.executor().userId()),
					"复核人必须独立于实际完成者");
		}
		require(!independent || !c.reviewer().userId().equals(c.deciderId()), "复核人必须独立于MRB发布者");
	}

	/**
	 * 人员快照必须来自实际实名事件，禁止占位人员。
	 */
	public static void actor(Actor a) {
		require(a != null, "返工实名人员缺失");
		id(a.userId());
		field(a.code(), 100, true);
		field(a.name(), 100, true);
	}

	/**
	 * 稳定随机原号不含组织或令牌，首次发送后不得换号。
	 */
	public static void request(String value) {
		require(value != null && value.matches("[A-Za-z0-9_-]{16,64}"), "返工原号须为16至64位安全随机文本");
	}

	/**
	 * 字符边界按原文检查，永久事实不静默裁剪。
	 */
	public static void field(String value, int max, boolean required) {
		require((!required || value != null && !value.isBlank()) && (value == null || value.length() <= max
				&& value.equals(value.trim()) && value.indexOf('\0') < 0), "返工字段缺失、超长或首尾空白");
	}

	public static void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
