package com.kitex.mom.wms.dto;

import java.util.List;
import java.util.Map;

/**
 * 仓储NCR原位整量返工V1合同；身份、数量和业务状态以文本传输，永久回执不含令牌。
 * 仅inspect输入的单位可空，其余永久事实必须保存从原操作影响库存证明的基本单位。
 */
public final class WmsNcrReworkDTO {
	private WmsNcrReworkDTO() {
	}

	/**
	 * @param tenantId            原案租户正整数ID
	 * @param factoryCode         原案实际工厂编码
	 * @param ncrId               原NCR主案ID
	 * @param detailId            当前分类明细ID
	 * @param detailNo            当前分类业务编号
	 * @param proposalId          已发布MRB提案ID
	 * @param rootHoldOperationId 首次成功HOLD操作ID
	 * @param parentOperationId   本次成功REWORK操作ID
	 * @param holdNo              原质量隔离单号
	 * @param quantity            分类整量，基本单位十进制定点文本
	 * @param unit                冻结实际基本单位；仅inspect输入可空
	 * @param binding             特殊退回395冻结原件；普通NCR必须为空
	 */
	public record Key(String tenantId, String factoryCode, String ncrId, String detailId,
	                  String detailNo, String proposalId, String rootHoldOperationId,
	                  String parentOperationId, String holdNo, String quantity, String unit,
	                  WmsSpecialReturnQualityDTO.Binding binding) {
	}

	/**
	 * @param stockId       精确受影响库存ID
	 * @param quantity      该行完整基本单位数量
	 * @param warehouseCode 实际原仓编码
	 * @param siteCode      冻结库位编码
	 * @param containerCode 冻结容器编码，可为空串
	 * @param identity      完整物理身份、货权、首次来源及库龄；沿用特殊库存证明空值规则
	 */
	public record Piece(String stockId, String quantity, String warehouseCode, String siteCode,
	                    String containerCode, Map<String, String> identity) {
	}

	/**
	 * @param version       固定版本1
	 * @param key           原案及原MRB操作身份，单位必填
	 * @param warehouseCode 从精确库存证明的唯一实际原仓
	 * @param pieces        按库存ID数值升序的完整库存片段，数量之和等于分类整量
	 */
	public record Source(int version, Key key, String warehouseCode, List<Piece> pieces) {
	}

	/**
	 * @param userId 实名人员主键ID
	 * @param code   事件当时账号或工号快照
	 * @param name   事件当时人员姓名快照
	 */
	public record Actor(String userId, String code, String name) {
	}

	/**
	 * @param version             固定版本1
	 * @param requestId           首次准备前生成的16至64位安全随机原号
	 * @param source              已核验并永久冻结的完整原件
	 * @param completionId        有效完成事实ID，未完工REJECT可空
	 * @param completionRequestId 完成登记原号，与completionId和executor同时有或同时为空
	 * @param executor            实际完成者快照；仅未完工REJECT可空
	 * @param reviewer            独立复核人快照，首次写必须等于当前实名人员
	 * @param deciderId           原MRB发布者ID，复核人须独立于发布者及实际完成者
	 * @param decision            独立复核结论PASS或REJECT
	 * @param reviewEvidence      独立检验依据及实测结果，1至2000字符
	 */
	public record Command(int version, String requestId, Source source, String completionId,
	                      String completionRequestId, Actor executor, Actor reviewer,
	                      String deciderId, String decision, String reviewEvidence) {
	}

	/**
	 * @param command    永久冻结的完整原命令
	 * @param status     永久终态APPLIED或CANCELLED
	 * @param resultId   WMS永久回执ID
	 * @param recordedBy 实际落WMS终态的实名人员快照
	 * @param recordedAt 服务端微秒精度ISO本地事件时间
	 * @param changes    APPLIED逐件前后状态；CANCELLED必须为空数组
	 */
	public record Receipt(Command command, String status, String resultId, Actor recordedBy,
	                      String recordedAt, List<Change> changes) {
	}

	/**
	 * @param stockId       原受影响库存ID
	 * @param quantity      前后不变的完整基本单位数量
	 * @param beforeStatus  办理前库存业务状态文本1
	 * @param beforeQuality 办理前质量状态REWORK
	 * @param beforeResult  办理前质量结果UN_QUALIFIED
	 * @param afterStatus   PASS为0，REJECT为1
	 * @param afterQuality  PASS为QUALIFIED，REJECT为HOLD
	 * @param afterResult   PASS为RELEASE，REJECT为UN_QUALIFIED
	 */
	public record Change(String stockId, String quantity, String beforeStatus, String beforeQuality,
	                     String beforeResult, String afterStatus, String afterQuality, String afterResult) {
	}

	/**
	 * @param state   只允许FOUND或NOT_FOUND，故障不能冒充NOT_FOUND
	 * @param receipt FOUND时为完整权威回执；NOT_FOUND时为空
	 */
	public record Lookup(String state, Receipt receipt) {
	}
}
