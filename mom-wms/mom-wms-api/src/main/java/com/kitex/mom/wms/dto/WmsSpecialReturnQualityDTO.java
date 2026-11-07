package com.kitex.mom.wms.dto;

import java.util.List;
import java.util.Map;

/**
 * 特殊退回原实物质量接管合同；身份与数量使用文本，候选不构成库存写入许可。
 */
public final class WmsSpecialReturnQualityDTO {
	private WmsSpecialReturnQualityDTO() {
	}

	/**
	 * @param contractVersion 固定为1的来源版本
	 * @param tenantId        原凭据所属租户ID
	 * @param factoryCode     实际工厂编码
	 * @param warehouseCode   实际仓库编码
	 * @param orderId         特殊收发原申请ID
	 * @param returnRequestId 已登记RETURN的永久请求号
	 * @param returnFlowId    本次退回入库实际流水ID
	 * @param sourceFlowId    原正向出库流水ID
	 * @param returnStockId   RETURN首次形成的库存ID，后续拆分不改写
	 * @param stockId         本次选中原件或可证隔离余量的库存ID
	 * @param billId          本次RETURN正式入库单ID，不是首次来源单
	 * @param billDetailId    本次RETURN正式入库明细ID
	 * @param stockQuantity   选择时该行完整库存量，单位见unit
	 * @param quantity        本案接管数量，单位见unit
	 * @param unit            冻结基本库存单位
	 * @param siteCode        冻结库位编码
	 * @param containerCode   冻结容器编码，可为空串
	 * @param identity        原件完整身份、首次来源、库龄及原ISOLATED质量证据
	 * @param fingerprint     服务端规范原件指纹，数量选择不改变候选指纹
	 */
	public record Target(int contractVersion, String tenantId, String factoryCode, String warehouseCode,
	                     String orderId, String returnRequestId, String returnFlowId, String sourceFlowId,
	                     String returnStockId, String stockId, String billId, String billDetailId,
	                     String stockQuantity, String quantity, String unit, String siteCode,
	                     String containerCode, Map<String, String> identity, String fingerprint) {
	}

	/**
	 * @param warehouseCode   当前实际仓库
	 * @param orderId         原申请ID，由候选选择
	 * @param returnRequestId 原RETURN请求号，由候选选择
	 * @param returnFlowId    原退回流水ID，由候选选择
	 * @param stockId         当前选定库存ID，由候选选择
	 * @param quantity        拟接管基本单位数量文本
	 * @param fingerprint     已观察候选指纹，预检必须重新证明
	 */
	public record Selection(String warehouseCode, String orderId, String returnRequestId,
	                        String returnFlowId, String stockId, String quantity, String fingerprint) {
	}

	/**
	 * @param target              永久冻结的规范原件
	 * @param rootHoldOperationId 本NCR首次HOLD操作ID；初次命令为空，结果返回实际ID
	 * @param reholdOperationId   验证驳回所针对的原处置操作ID；仅重隔离命令必填
	 */
	public record Binding(Target target, String rootHoldOperationId, String reholdOperationId) {
	}

	/**
	 * @param target    经实际永久账证明的当前隔离实物
	 * @param reference 原申请业务参照
	 * @param billNo    原退回正式入库单号
	 * @param kind      原特殊业务种类
	 */
	public record Candidate(Target target, String reference, String billNo, String kind) {
	}

	/**
	 * @param warehouseCode 必选实际仓库
	 * @param orderId       可选原申请ID，由业务选择器传入
	 * @param reference     可选业务参照或退回单号检索
	 * @param page          页码，从1开始
	 * @param size          每页数量，最大50
	 */
	public record CandidateQuery(String warehouseCode, String orderId, String reference, int page, int size) {
	}

	/**
	 * @param records 当前页真实候选
	 * @param page    实际页码
	 * @param size    实际每页上限
	 * @param total   当前可证候选总数；单次扫描上限500条退回凭据，超限拒绝并要求缩小筛选
	 */
	public record CandidatePage(List<Candidate> records, int page, int size, long total) {
	}
}
