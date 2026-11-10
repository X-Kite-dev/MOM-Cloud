package com.kitex.mom.pms.dto;

import com.kitex.common.core.exception.CheckedException;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/**
 * WMS 正式采购入库过账事实。原单和逐行身份稳定，同一原单的重放必须保持内容一致。
 *
 * @param factoryCode 当前实名工厂编码
 * @param warehouseCode WMS 原仓编码
 * @param wmsBillId WMS 已过账入库单主键
 * @param wmsBillNo WMS 已过账入库单号
 * @param wmsNoticeId WMS 原采购入库通知主键
 * @param purchaseReceiptId PMS 原采购收料单主键
 * @param lines 已过账的完整实收明细
 */
@Schema(description = "WMS采购入库原单永久回写V1")
public record PmsPurchaseInboundPosting(
		@Schema(description = "实际工厂编码") String factoryCode,
		@Schema(description = "原入库仓库编码") String warehouseCode,
		@Schema(description = "WMS原入库单ID") Long wmsBillId,
		@Schema(description = "WMS原入库单号") String wmsBillNo,
		@Schema(description = "WMS原采购入库通知ID") Long wmsNoticeId,
		@Schema(description = "PMS原采购收料单ID") Long purchaseReceiptId,
		@Schema(description = "完整实收入库明细") List<Line> lines) {

	/**
	 * 一条正式 WMS 过账明细。允许同一 PMS 批次在 WMS 按库位或容器拆行。
	 *
	 * @param wmsDetailId WMS 原入库明细主键
	 * @param receiptBatchId PMS 原收料批次明细主键
	 * @param orderDetailId PMS 原采购订单明细主键
	 * @param materialCode 原物料编码
	 * @param batchNo 原采购收料批次
	 * @param unit 标准库存单位
	 * @param quantity 本原明细已过账的标准单位正数量
	 */
	public record Line(
			@Schema(description = "WMS入库明细ID") Long wmsDetailId,
			@Schema(description = "PMS收料批次明细ID") Long receiptBatchId,
			@Schema(description = "PMS采购订单明细ID") Long orderDetailId,
			@Schema(description = "物料编码") String materialCode,
			@Schema(description = "收料批次") String batchNo,
			@Schema(description = "标准库存单位") String unit,
			@Schema(description = "已过账标准数量") BigDecimal quantity) {
	}

	/**
	 * PMS 持久回执。同号同事实重复投递返回同一回执。
	 *
	 * @param wmsBillId 原入库单主键
	 * @param purchaseReceiptId 原采购收料单主键
	 * @param fingerprint 事务内已受理完整事实的 SHA-256
	 * @param lineCount 实际受理的 WMS 明细条数
	 */
	public record Receipt(
			@Schema(description = "WMS原入库单ID") Long wmsBillId,
			@Schema(description = "PMS原采购收料单ID") Long purchaseReceiptId,
			@Schema(description = "受理事实指纹") String fingerprint,
			@Schema(description = "受理明细数") int lineCount) {
	}

	/**
	 * 校验永久来源、明细唯一性和数量边界，工厂/仓库及采购权威事实由办理服务另行核实。
	 */
	public void validate() {
		if (!text(factoryCode, 100) || !text(warehouseCode, 100) || !positive(wmsBillId)
				|| !text(wmsBillNo, 100) || !positive(wmsNoticeId) || !positive(purchaseReceiptId)
				|| lines == null || lines.isEmpty() || lines.size() > 1000) {
			throw new CheckedException("采购入库回写来源或明细范围不完整");
		}
		var ids = new HashSet<Long>();
		for (Line line : lines) {
			if (line == null || !positive(line.wmsDetailId()) || !ids.add(line.wmsDetailId())
					|| !positive(line.receiptBatchId()) || !positive(line.orderDetailId())
					|| !text(line.materialCode(), 200) || !text(line.batchNo(), 200)
					|| !text(line.unit(), 100) || !validQuantity(line.quantity())) {
				throw new CheckedException("采购回写明细的原行、批次、单位或数量无效");
			}
		}
	}

	/**
	 * 计算 V1 冻结指纹。先按 WMS 行主键排序，数量去尾零，字段用长度分隔，防止分隔符碰撞。
	 *
	 * @return 小写 SHA-256 十六进制
	 */
	public String fingerprint() {
		validate();
		var canonical = new StringBuilder("WMS_PURCHASE_INBOUND_V1;");
		append(canonical, factoryCode, warehouseCode, wmsBillId, wmsBillNo, wmsNoticeId,
				purchaseReceiptId, lines.size());
		for (Line line : lines.stream().sorted(Comparator.comparing(Line::wmsDetailId)).toList()) {
			append(canonical, line.wmsDetailId(), line.receiptBatchId(), line.orderDetailId(), line.materialCode(),
					line.batchNo(), line.unit(), line.quantity().stripTrailingZeros().toPlainString());
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
		}
	}

	private static boolean validQuantity(BigDecimal value) {
		return value != null && value.signum() > 0 && value.stripTrailingZeros().scale() <= 6
				&& value.compareTo(new BigDecimal("1000000000000000000")) < 0;
	}

	private static boolean text(String value, int max) {
		return value != null && !value.isBlank() && value.length() <= max && value.equals(value.trim());
	}

	private static boolean positive(Long value) {
		return value != null && value > 0;
	}

	private static void append(StringBuilder result, Object... values) {
		for (Object value : values) {
			String text = value.toString();
			result.append(text.length()).append(':').append(text);
		}
	}
}
