package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.util.WmsNoticeQueryIdentity;

import com.kitex.common.core.exception.CheckedException;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入出库单主表、统计及明细共用查询参数
 *
 * @author kitex
 * @date 2024-12-17 11:21:00
 */
@Data
public class WmsInBillDetailQueryDTO {

	/**
	 * 入库单号
	 */
	private String billNo;

	/**
	 * 完整原入库／出库通知单主键文本；为空时不按通知筛选，不支持片段匹配。
	 */
	private String noticeId;

	/**
	 * 状态
	 */
	private String state;

	/**
	 * 状态列表
	 */
	private List<String> stateList;

	/**
	 * 物料编码
	 */
	private String matCode;

	/**
	 * 仓库编码
	 */
	private String warehouseCode;

	/**
	 * 创建人
	 */
	private String createBy;

	/**
	 * 创建时间
	 */
	private List<LocalDateTime> createTime;

	/**
	 * 单据类型
	 */
	private String billType;

	/**
	 * 单据类型
	 */
	private List<String> billTypes;

	/**
	 * 服务端覆盖的授权仓库集合；null沿既有查询，空集合明确无可读记录。
	 */
	@io.swagger.v3.oas.annotations.media.Schema(hidden = true)
	private List<String> permittedWarehouseCodes;

	/**
	 * 服务端实际租户ID；其他业务查询在派发前覆盖，不能用请求值扩大范围。
	 */
	@io.swagger.v3.oas.annotations.media.Schema(hidden = true)
	private Long scopeTenantId;

	/**
	 * 服务端当前实际工厂编码；与主表和明细组织同时核对。
	 */
	@io.swagger.v3.oas.annotations.media.Schema(hidden = true)
	private String scopeFactoryCode;

	/**
	 * 将查询输入解析为数据库使用的完整通知身份，不改变对外字符串传输合同。
	 *
	 * @return 未填写时返回null，否则返回正Long主键
	 * @throws CheckedException 已填写但不是完整正整数，或超出Long范围时拒绝查询
	 */
	public Long noticeIdValue() {
		return WmsNoticeQueryIdentity.optionalId(noticeId);
	}

}
