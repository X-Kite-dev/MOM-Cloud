package com.kitex.mom.wms.dto.app;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 手机普通收发的待办通知候选，不替代选择后的正式来源核验或实际可用量判断。
 */
public final class WmsMobileNoticeListDTO {
	private WmsMobileNoticeListDTO() {
	}

	/**
	 * @param contractVersion 本候选列表合同版本，当前为1
	 * @param tenantId        实际租户标识的完整十进制文本
	 * @param factoryCode     当前已获实名read授权的工厂
	 * @param warehouseCode   当前具有出入库权限并启用手机业务的实际仓库
	 * @param billType        查询入口业务类型，其他出库包含其已启用细分类型
	 * @param page            已成功读取页码，1至100，每页最多20条
	 * @param hasMore         当前快照是否还有后续候选，不代表后续页的库存可用性
	 * @param records         待处理或处理中通知，只提供选择所需摘要
	 */
	public record Page(int contractVersion, String tenantId, String factoryCode, String warehouseCode,
	                   String billType, int page, boolean hasMore, List<Item> records) {
	}

	/**
	 * @param noticeId        实际通知主键的完整文本，选中后用于重新读取正式来源
	 * @param billType        原通知保存的实际业务类型
	 * @param state           原通知状态：0待处理、1处理中
	 * @param warehouseCode   原通知所属实际仓库
	 * @param warehouseName   原通知仓库名称快照，可为空
	 * @param sourceReference 原业务单据标识文本，可为空，不伪造外部单号
	 * @param materialCode    原通知首项物料编码预览，可为空，不代表完整明细清单
	 * @param materialName    原通知首项物料名称快照，可为空
	 * @param remark          原通知备注，可为空
	 * @param createTime      通知创建时间，可为空
	 * @param createBy        原创建账号快照，可为空
	 */
	public record Item(String noticeId, String billType, String state, String warehouseCode,
	                   String warehouseName, String sourceReference, String materialCode, String materialName,
	                   String remark,
	                   LocalDateTime createTime, String createBy) {
	}
}
