package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * 真实出库事务的不可变逐来源回执，仅由内部业务服务取得。
 *
 * <p>回执来自本次必要写成功并回读的实际OUT流水，不是预检、预留或历史匹配结果。
 * STOCK_LINE_V1仍只证明核心库存操作；本回执不代替永久生产操作账、投料出生全集及数据库OLD/NEW证明。</p>
 */
@Schema(description = "线边库存真实逐来源出库回执")
public record MesWarehouseStockOutReceiptDTO(
		/** 逐来源回执版本；不补造旧记录缺失的证据。 */
		String version,
		/** 本次认证上下文的租户主键。 */
		Long tenantId,
		/** 本次已授权的实际工厂编码。 */
		String factoryCode,
		/** 发起本次真实出库的业务主键。 */
		Long billId,
		/** 本次出库关联的业务单号。 */
		String billNo,
		/** 出库业务类型，沿所属业务枚举。 */
		String bizType,
		/** 本次请求的实际物料编码。 */
		String materialCode,
		/** 实际扣量的原物料单位。 */
		String materialUnit,
		/** 本次原物料单位的精确总扣量。 */
		BigDecimal quantity,
		/** 按真实分配顺序排列的全部OUT来源，不允许截断或修改。 */
		List<Line> lines) {

	/**
	 * 规范数值scale并冻结完整来源集合；集合为空引用时拒绝构造。
	 */
	public MesWarehouseStockOutReceiptDTO {
		quantity = normalize(quantity);
		lines = List.copyOf(lines);
	}

	/**
	 * SQL可能补尾零；数值比较不把无意义的scale差异当作事实变更。
	 */
	private static BigDecimal normalize(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros();
	}

	/**
	 * 内部投料服务选定的原库存谱系过滤条件，不是库存来源证明。
	 * 谱系字段保留原NULL/空文本区别；未预留库存也必须精确过滤，不能因NULL省略条件而串用有预留库存。
	 * 可选库存主键只缩小已核准的候选范围；原行余额不足时不能改扣另一行。
	 */
	public record Lineage(
			/** 原库存预留号；保留NULL与空文本的区别。 */
			String reservationNo,
			/** 原库存绑定的需求物料编码；允许按原来源为空。 */
			String requirementMaterialCode,
			/** 本次选定的原库存主键；为空沿旧候选分配规则。 */
			Long stockId) {

		/**
		 * 兼容旧调用，仅保留原谱系过滤，不指定库存主键。
		 */
		public Lineage(String reservationNo, String requirementMaterialCode) {
			this(reservationNo, requirementMaterialCode, null);
		}
	}

	/**
	 * 单个实际库存来源的一次真实OUT，保留库存自己的工序归属，不改为接收派工。
	 * 源GOOD报告、签收及预留均可能为空，空值不代表已查明来源。
	 */
	@Schema(description = "一次实际库存行扣减及其已保存流水")
	public record Line(
			/** 本次实际插入的OUT流水主键。 */
			Long inoutId,
			/** 本次被扣减的原库存主键。 */
			Long stockId,
			/** 库存原GOOD报告主键；原来源缺失时为空。 */
			Long sourceReportId,
			/** 库存原签收主键；不是本次业务主键，可为空。 */
			Long materialReceiptId,
			/** 原库存与本次操作共同所属的实际租户。 */
			Long tenantId,
			/** 原库存与本次操作共同所属的实际工厂。 */
			String factoryCode,
			/** 本次实际出库关联的业务主键。 */
			Long billId,
			/** 本次实际出库关联的业务单号。 */
			String billNo,
			/** 本次业务的永久原请求号。 */
			String resourceNo,
			/** 本次真实OUT的业务类型。 */
			String bizType,
			/** 库存原工序归属；不覆盖为接收派工，可按原记录为空。 */
			String sourceProduceTaskCode,
			/** 原库存实际物料编码。 */
			String materialCode,
			/** 原库存实际物料单位，也是本行数量单位。 */
			String materialUnit,
			/** 原库存物料批次；按原来源保留空值。 */
			String batchNo,
			/** 原库存实物条码；按原来源保留空值。 */
			String barcode,
			/** 原库存预留号；按原来源保留空值。 */
			String reservationNo,
			/** 原库存的需求物料绑定，非今日BOM反推值。 */
			String requirementMaterialCode,
			/** 本次实际主替代标记；未知值不能按主料推断。 */
			Boolean substitute,
			/** 原需求当量换算为实际物料数量的精确比例。 */
			BigDecimal substituteRatio,
			/** 本行原物料单位的实际扣量。 */
			BigDecimal quantity,
			/** 本次扣减前的原库存余额，单位同materialUnit。 */
			BigDecimal beforeQuantity,
			/** 本次扣减后的原库存余额，单位同materialUnit。 */
			BigDecimal afterQuantity,
			/** 原库存证据协议；缺失不视为已证明。 */
			String proofVersion,
			/** 本次原选择政策原文，不以今日配置补写。 */
			String allocationPolicy) {

		/**
		 * 规范十进制scale；不改变真实数量、比例或原单位。
		 */
		public Line {
			substituteRatio = normalize(substituteRatio);
			quantity = normalize(quantity);
			beforeQuantity = normalize(beforeQuantity);
			afterQuantity = normalize(afterQuantity);
		}
	}
}
