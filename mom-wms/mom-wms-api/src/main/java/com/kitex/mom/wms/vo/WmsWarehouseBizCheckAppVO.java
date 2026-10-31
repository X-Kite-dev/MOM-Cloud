package com.kitex.mom.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 仓库业务选择-App
 *
 * @author kitex
 * @date 2025/1/13 18:07
 */
@Data
@Accessors(chain = true)
@Schema(description = "仓库业务选择-App")
public class WmsWarehouseBizCheckAppVO {
	/**
	 * 仓库编码
	 */
	@Schema(description = "仓库编码")
	private String warehouseCode;

	@Schema(description = "当前仓库是否启用手机端作业")
	private boolean mobileEnabled;

	@Schema(description = "手机端作业状态")
	private String mobileMessage;
	/**
	 * 入库出库
	 */
	@Schema(description = "入库出库")
	private List<WmsWarehouseBizCheckAppItemVO> inout;
	/**
	 * 通用
	 */
	@Schema(description = "通用")
	private List<WmsWarehouseBizCheckAppItemVO> common;
	/**
	 * 其他
	 */
	@Schema(description = "其他")
	private List<WmsWarehouseBizCheckAppItemVO> other;
}
