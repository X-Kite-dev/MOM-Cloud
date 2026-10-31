package com.kitex.mom.wms.dto.app;

import java.util.List;

/**
 * 本人近三十天的永久回执；查无记录不证明原请求未执行。
 */
public final class WmsMobileReceiptHistoryDTO {

	private WmsMobileReceiptHistoryDTO() {
	}

	public record Page<T>(int contractVersion, int page, boolean hasMore, List<T> records) {
	}
}
