package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.dto.WmsCorrectionTerminationDTO.Ended;

/**
 * 原办理回执核对；NOT_CONFIRMED不证明原操作未受理，不允许更换原号。
 */
public final class WmsFinishedGoodsCorrectionDecisionDTO {

	private WmsFinishedGoodsCorrectionDecisionDTO() {
	}

	/**
	 * @param action        原动作APPROVE批准、REJECT驳回或WITHDRAW撤回
	 * @param correctionId  同组织原红冲申请ID
	 * @param noticeId      原申请对应的成品入库通知ID
	 * @param warehouseCode 原申请全部冻结明细所属仓库，不能跨仓核对
	 * @param requestId     原办理永久请求号；批准固定为APPROVE:原申请ID
	 * @param reason        原办理正文原因，批准固定为“批准执行”，不是结束原因
	 */
	public record Query(String action, Long correctionId, Long noticeId, String warehouseCode,
	                    String requestId, String reason) {
	}

	/**
	 * @param original 原账号保存的完整办理动作、原申请及原号，不能换正文结束
	 * @param reason   结束该未执行请求的原因，去首尾空白后1至500字
	 */
	public record EndCommand(Query original, String reason) {
	}

	/**
	 * @param contractVersion 固定2，兼容原V1办理登记回执
	 * @param status          REGISTERED已办理、NOT_CONFIRMED未确认、ENDED已永久结束
	 * @param registration    原办理登记或未确认回执；已结束时为空
	 * @param termination     原号永久结束凭据；未结束时为空，不表示撤回原申请
	 */
	public record Outcome(int contractVersion, String status, Receipt registration, Ended termination) {
	}

	/**
	 * @param contractVersion    原登记协议固定1，由V2终态包装兼容返回
	 * @param status             REGISTERED已登记或NOT_CONFIRMED未确认
	 * @param requestId          原办理请求号
	 * @param tenantId           真实租户ID文本
	 * @param factoryCode        原申请实际工厂
	 * @param operatorCode       原办理实名账号
	 * @param warehouseCode      原申请冻结仓库
	 * @param noticeId           原成品入库通知ID文本
	 * @param correctionId       原红冲申请ID文本
	 * @param correctionNo       原红冲申请业务单号
	 * @param appliedTime        实际办理时间；未确认时为空
	 * @param quantity           兼容原回执字段，办理动作不新增实物数量，固定为空
	 * @param commandFingerprint 原办理命令SHA256；未确认时为空
	 * @param requestPayload     含原动作的完整规范化办理正文；未确认时为空
	 * @param action             原办理动作
	 * @param operationType      实际登记的办理履历类型；未确认时为空
	 * @param beforeStatus       原履历办理前状态SUBMITTED；未确认时为空
	 * @param afterStatus        原履历办理后状态；未确认时为空
	 */
	public record Receipt(int contractVersion, String status, String requestId, String tenantId,
	                      String factoryCode, String operatorCode, String warehouseCode, String noticeId,
	                      String correctionId, String correctionNo, String appliedTime, String quantity,
	                      String commandFingerprint, String requestPayload, String action, String operationType,
	                      String beforeStatus, String afterStatus) {
	}
}
