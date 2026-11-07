package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.util.WmsNoticeQueryIdentity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入出库通知单明细共用查询参数
 *
 * @author kitex
 * @date 2024-12-10 15:11:37
 */
@Data
public class WmsInNoticeDetailQueryDTO {

	/**
	 * 完整原入库／出库通知单主键文本；为空时不按通知筛选，不支持片段匹配。
	 */
	private String noticeId;

	/**
	 * 状态
	 */
	private String state;

	/**
	 * 仓库编码
	 */
	private String warehouseCode;

	/**
	 * 物料编码
	 */
	private String matCode;

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
	 * 单据类型(其他出入库查询时)
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
	 * 将外部字符串查询转换为完整原通知身份，不暴露新的JSON或绑定字段。
	 *
	 * @return 未填写时返回null，否则返回正Long主键；非法格式拒绝查询
	 */
	public Long noticeIdValue() {
		return WmsNoticeQueryIdentity.optionalId(noticeId);
	}

}
