package com.kitex.mom.wms.dto;

import java.util.List;
import java.util.Map;

/**
 * 整笔质量实物执行V1合同；所有业务ID及基本单位数量保持JSON文本。
 */
public final class WmsQualityPhysicalDTO {
	private WmsQualityPhysicalDTO() {
	}

	/**
	 * 原操作不可变身份与原指定条件。
	 *
	 * @param tenantId             原租户正ID文本
	 * @param factoryCode          实际工厂编码
	 * @param operationId          原处置正ID文本
	 * @param operationRequestId   QMS原处置请求号
	 * @param sourceType           原质量来源类型
	 * @param sourceId             原来源业务文本
	 * @param holdNo               原隔离单号
	 * @param action               原处置动作SCRAP或RETURN
	 * @param matCode              原物料编码
	 * @param matBatch             原指定批次，可空
	 * @param matUnit              原指定基本单位，可空
	 * @param supplierCode         原指定供应商，可空
	 * @param warehouseCode        原仓编码
	 * @param siteCode             原库位，可空
	 * @param containerCode        原容器，可空
	 * @param requestedQuantity    原请求基本单位数量规范文本
	 * @param affectedQuantity     原实际基本单位数量规范文本
	 * @param specialReturnBinding 特殊395永久绑定，普通为空
	 */
	public record Key(String tenantId, String factoryCode, String operationId, String operationRequestId,
	                  String sourceType, String sourceId, String holdNo, String action, String matCode,
	                  String matBatch, String matUnit, String supplierCode, String warehouseCode,
	                  String siteCode, String containerCode, String requestedQuantity, String affectedQuantity,
	                  WmsSpecialReturnQualityDTO.Binding specialReturnBinding) {
	}

	/**
	 * 原实物片段及完整库存身份。
	 *
	 * @param stockId             原库存正ID文本
	 * @param quantity            本片段基本单位数量规范文本
	 * @param warehouseCode       原仓编码
	 * @param siteCode            原库位，可空
	 * @param containerCode       原容器，可空
	 * @param unit                真实基本单位
	 * @param stockStatus         现场状态文本固定1
	 * @param sourceStockId       旧库存源业务文本，可空
	 * @param physicalOperationId 当前实物处置owner正ID文本，旧可证记录可空
	 * @param identity            全部原库存身份键值文本，保留空值规则
	 */
	public record Piece(String stockId, String quantity, String warehouseCode, String siteCode,
	                    String containerCode, String unit, String stockStatus, String sourceStockId,
	                    String physicalOperationId, Map<String, String> identity) {
	}

	/**
	 * 完整冻结来源，集合顺序属于合同。
	 *
	 * @param v      合同版本，固定1
	 * @param key    完整原操作身份
	 * @param pieces 数值ID升序的完整非空片段
	 */
	public record Source(int v, Key key, List<Piece> pieces) {
	}

	/**
	 * 实名原号完整办理意图。
	 *
	 * @param v            合同版本，固定1
	 * @param operationId  原处置正ID文本
	 * @param requestId    本次永久原号
	 * @param actorId      当前实名正ID文本
	 * @param source       完整冻结来源，执行和结束原号均必填
	 * @param executionRef 实物执行凭证原文
	 * @param remark       实物执行说明原文
	 */
	public record Command(int v, String operationId, String requestId, String actorId, Source source,
	                      String executionRef, String remark) {
	}

	/**
	 * 永久APPLIED或CANCELLED终态。
	 *
	 * @param v           合同版本，固定1
	 * @param outcome     原号结果种类
	 * @param command     永久保存的完整规范命令
	 * @param endedById   原实际办理人员正ID文本
	 * @param endedBy     原实际账号快照
	 * @param endedByName 原实际姓名快照
	 * @param endedTime   数据库原业务时间ISO本地文本
	 */
	public record Receipt(int v, String outcome, Command command, String endedById, String endedBy,
	                      String endedByName, String endedTime) {
	}

	/**
	 * 真实持久执行资料投影，不回填历史。
	 *
	 * @param executionRequestId 原执行请求号，历史据实可空
	 * @param executionRef       实物执行凭证原文
	 * @param executionRemark    原执行说明，历史据实可空
	 * @param executedById       可证实际执行人正ID文本，历史可空
	 * @param executedBy         原执行账号快照，历史可空
	 * @param executedByName     原执行姓名快照，历史可空
	 * @param executedTime       数据库原执行时间，历史可空
	 */
	public record Execution(String executionRequestId, String executionRef, String executionRemark,
	                        String executedById, String executedBy, String executedByName,
	                        String executedTime) {
	}

	/**
	 * 经过完整原仓授权的操作行。
	 *
	 * @param id                      原操作正ID文本
	 * @param tenantId                原租户正ID文本
	 * @param factoryCode             实际工厂编码
	 * @param requestId               本次永久原号
	 * @param holdNo                  原隔离单号
	 * @param sourceType              原质量来源类型
	 * @param sourceId                原来源业务文本
	 * @param action                  原处置动作SCRAP或RETURN
	 * @param matCode                 原物料编码
	 * @param matBatch                原指定批次，可空
	 * @param requestedQuantity       原请求基本单位数量规范文本
	 * @param affectedQuantity        原实际基本单位数量规范文本
	 * @param unit                    真实基本单位
	 * @param affectedStockIds        完整影响库存ID文本集合
	 * @param warehouseCodes          已证原仓去重稳定排序集合
	 * @param warehouseBasis          原仓证据类别
	 * @param status                  原操作四态
	 * @param effect                  SUPERSEDED、EXECUTED或UNVERIFIED
	 * @param supersededByOperationId 成功定向重隔离操作ID，可空
	 * @param resultMessage           原业务结果说明，可空
	 * @param executionRef            实物执行凭证原文
	 * @param createTime              原创建业务时间，可空
	 * @param canExecute              当前人员可首次实物办理
	 * @param canRetry                当前人员可同步原命令
	 */
	public record PageRow(String id, String tenantId, String factoryCode, String requestId, String holdNo,
	                      String sourceType, String sourceId, String action, String matCode, String matBatch,
	                      String requestedQuantity, String affectedQuantity, String unit,
	                      List<String> affectedStockIds, List<String> warehouseCodes, String warehouseBasis,
	                      String status, String effect, String supersededByOperationId, String resultMessage,
	                      String executionRef, String createTime, boolean canExecute, boolean canRetry) {
	}

	/**
	 * 当前受权详情及可供冻结的来源。
	 *
	 * @param v              合同版本，固定1
	 * @param viewerId       本次受权读取人员稳定正ID文本
	 * @param operation      受权原操作投影
	 * @param evidence       FROZEN、LEGACY_PARTIAL或OBSERVED
	 * @param source         完整冻结来源，不可办理时为空
	 * @param appliedReceipt 本业务永久APPLIED回执，可空
	 * @param execution      原已执行事实投影，可空
	 * @param blockedReason  不能首次办理的原因，可空
	 * @param nextRequestId  仅可执行GET详情建议PX1原号，无预占，可空
	 */
	public record Detail(int v, String viewerId, PageRow operation, String evidence, Source source,
	                     Receipt appliedReceipt, Execution execution, String blockedReason,
	                     String nextRequestId) {
	}

	/**
	 * 原号核对结果，失败不冒充OPEN。
	 *
	 * @param v           合同版本，固定1
	 * @param operationId 原处置正ID文本
	 * @param requestId   本次永久原号
	 * @param outcome     原号结果种类
	 * @param receipt     同号永久终态回执，OPEN等为空
	 * @param detail      受权详情，最窄本人取消可空
	 */
	public record Lookup(int v, String operationId, String requestId, String outcome, Receipt receipt,
	                     Detail detail) {
	}
}
