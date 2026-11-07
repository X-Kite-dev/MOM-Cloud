package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * WMS永久质量操作回执；特殊原件结果携冻结绑定与实际影响库存身份。
 */
@Data
@Accessors(chain = true)
public class WmsQualityStatusResult {
	/**
	 * 原已执行持久事实，未执行为空，历史缺项不补造。
	 */
	private WmsQualityPhysicalDTO.Execution execution;
	/**
	 * 普通定向HOLD对应原处置ID文本；其他结果为空。
	 */
	private String reholdOfPhysicalOperationId;
	/**
	 * 首次成功重隔离本处置的永久操作ID；只读派生，非空表示原处置已永久失效，普通来源为空。
	 */
	private String supersededByOperationId;
	/**
	 * 永久特殊原件及root/被驳回处置引用；普通来源为空。
	 */
	private WmsSpecialReturnQualityDTO.Binding specialReturnBinding;
	/**
	 * 实际影响库存ID文本集合；不能按批次推测原件。
	 */
	private List<String> affectedStockIds;
	/**
	 * WMS永久质量操作ID。
	 */
	private Long operationId;
	/**
	 * 正式质量隔离单号，处置及重隔离保持首次QH。
	 */
	private String holdNo;
	/**
	 * 质量动作：HOLD、RELEASE、CONCESSION、REWORK、SCRAP或RETURN。
	 */
	private String action;
	/**
	 * 操作结果：PENDING、SUCCESS、ACTION_PENDING或EXECUTED。
	 */
	private String status;
	/**
	 * 原请求基本库存单位数量。
	 */
	private BigDecimal requestedQuantity;
	/**
	 * 已实际影响基本库存单位数量。
	 */
	private BigDecimal affectedQuantity;
	/**
	 * 永久操作回执的业务结果说明。
	 */
	private String message;
}
