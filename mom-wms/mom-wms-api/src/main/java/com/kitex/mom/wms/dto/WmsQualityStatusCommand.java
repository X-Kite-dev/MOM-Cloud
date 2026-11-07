package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * QMS驱动WMS质量库存状态的幂等命令。
 */
@Data
@Accessors(chain = true)
public class WmsQualityStatusCommand {
	/**
	 * 普通定向重隔离的原处置正ID文本；其他命令为空。
	 */
	private String reholdOfPhysicalOperationId;
	/**
	 * 普通定向重隔离的受保护父请求号；与父ID成对提供。
	 */
	private String reholdOfPhysicalRequestId;
	/**
	 * 特殊RETURN专用永久原件绑定；普通来源必须为空。
	 */
	private WmsSpecialReturnQualityDTO.Binding specialReturnBinding;
	/**
	 * 租户内稳定幂等请求号，重试必须保持原号及原命令。
	 */
	private String requestId;
	/**
	 * 正式质量隔离单号，处置及重隔离保持首次QH。
	 */
	private String holdNo;
	/**
	 * 正式质量来源类型，QMS案件固定QMS_NCR。
	 */
	private String sourceType;
	/**
	 * 正式来源案件ID文本，不是特殊申请或库存ID。
	 */
	private String sourceId;
	/**
	 * 质量动作：HOLD、RELEASE、CONCESSION、REWORK、SCRAP或RETURN。
	 */
	private String action;
	/**
	 * 冻结物料编码。
	 */
	private String matCode;
	/**
	 * 冻结批次，可为空。
	 */
	private String matBatch;
	/**
	 * 数量对应的库存单位；召回等批量隔离必须按单位分别下达命令。
	 */
	private String matUnit;
	/**
	 * 冻结供应商编码，可为空。
	 */
	private String supplierCode;
	/**
	 * 实际库存仓库编码，特殊原件必须与冻结目标相同。
	 */
	private String warehouseCode;
	/**
	 * 冻结库位编码，可为空。
	 */
	private String siteCode;
	/**
	 * 冻结容器编码，可为空。
	 */
	private String containerCode;
	/**
	 * 本次质量动作数量，按matUnit基本库存单位精确计量。
	 */
	private BigDecimal quantity;
	/**
	 * 本次质量动作业务原因。
	 */
	private String reason;
	/**
	 * 当前实际工厂编码，必须通过人员工厂及原仓授权。
	 */
	private String factoryCode;
}
