package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.dto.WmsQualityActionExecuteCommand;
import com.kitex.mom.wms.dto.WmsQualityStatusCommand;
import com.kitex.mom.wms.dto.WmsQualityStatusResult;
import com.kitex.mom.wms.entity.WmsQualityStatusOperationEntity;

/**
 * 正式质量库存命令及永久回执；特殊来源需在锁内证明精确原件。
 */
public interface WmsQualityStatusService extends IService<WmsQualityStatusOperationEntity> {
	/**
	 * 提交原号质量命令，特殊来源在事务锁内重证原件，成功重放返回永久回执。
	 */
	WmsQualityStatusResult apply(WmsQualityStatusCommand command);

	/**
	 * 普通原处置父定向整件重隔离，只允许专用内部路由。
	 */
	WmsQualityStatusResult applyPhysicalRehold(WmsQualityStatusCommand command);

	/**
	 * 按永久操作ID重试，特殊来源不得重新匹配或变更冻结实物。
	 */
	WmsQualityStatusResult retry(Long operationId);

	/**
	 * 凭正式执行单确认报废或退货，已EXECUTED不能由重隔离复活。
	 */
	WmsQualityStatusResult execute(WmsQualityActionExecuteCommand command);
}
