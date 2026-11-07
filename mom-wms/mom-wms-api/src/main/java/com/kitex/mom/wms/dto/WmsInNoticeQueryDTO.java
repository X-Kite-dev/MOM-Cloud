package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.util.WmsNoticeQueryIdentity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入出库通知单主表与统计共用查询参数
 *
 * @author kitex
 * @date 2024/12/13 13:54
 */
@Data
public class WmsInNoticeQueryDTO {

	/**
	 * 完整原入库／出库通知单主键文本；为空时不按通知筛选，不支持片段匹配。
	 */
	private String id;

	/**
	 * 入库通知单状态;0待处理、1处理中、2已完成、3已作废
	 */
	private String state;

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
	 * 将外部字符串查询转换为完整原通知身份，不暴露新的JSON或绑定字段。
	 *
	 * @return 未填写时返回null，否则返回正Long主键；非法格式拒绝查询
	 */
	public Long noticeIdValue() {
		return WmsNoticeQueryIdentity.optionalId(id);
	}

}
