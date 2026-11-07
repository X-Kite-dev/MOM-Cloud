package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 已完成录入、尚未调账的盘点明细复盘命令。
 */
@Data
public class WmsInventoryRecountCommand {
	@Schema(description = "原盘点明细ID")
	private Long planDetailId;

	@Schema(description = "页面观察到的原实盘数量")
	private Double expectedInventoryNum;

	@Schema(description = "复盘数量")
	private Double recountNum;

	@Schema(description = "复盘原因，最多500字")
	private String reason;

	@Schema(description = "客户端稳定请求号，重复提交返回原结果")
	private String requestId;
	@Schema(description = "分货权库存依据版本；与ownerCounts一同提供")
	private String ownerBasisVersion;
	@Schema(description = "打开时原明细最近复盘记录；无记录时为空")
	private String ownerExpectedRecountId;
	@Schema(description = "按服务端分组完整填写的实盘数量；合计由服务端计算")
	private List<OwnerCount> ownerCounts;

	public record OwnerCount(String key, String quantity) {
	}
}
