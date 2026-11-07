package com.kitex.mom.wms.dto;

import lombok.Data;

/**
 * 保留完整原命令，结束原因不覆盖原处理依据。
 */
@Data
public class WmsMesIssueCancelCommand {
	private WmsMesIssueCommand original;
	private String reason;
}
