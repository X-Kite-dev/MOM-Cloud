package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.app.WmsInBillDetailAppDTO;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.service.WmsMobileInboundReceiptService;
import com.kitex.mom.wms.service.WmsReturnReceiptAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

/**
 * 移动端入库单
 * 兼容地址复用正式收货服务的组织与仓库准入；新作业台仍使用永久原请求接口。
 *
 * @author kitex
 * @date 2024/12/30 15:31
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/inBill")
@Tag(description = "AppInBillController", name = "移动端入库单")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class AppInBillController {

	private final WmsMobileInboundReceiptService mobileReceiptService;
	private final WmsReturnReceiptAccessService returnReceiptAccess;

	/**
	 * 查询入库通知单详情
	 *
	 * @return R  对象
	 */
	@Operation(summary = "查询入库通知单详情", description = "查询入库通知单详情")
	@GetMapping("/detailsById")
	public R<WmsInNoticeEntity> getDetails(@RequestParam Long id) {
		WmsInNoticeEntity notice = mobileReceiptService.sources(id);
		if (notice != null && "return".equals(notice.getBillType())
				&& "mom_mes.mes_warehouse_stock_feed_judge".equals(notice.getDocumentTable())) {
			notice.setReturnReceiptCapabilities(returnReceiptAccess.capabilities(notice));
		}
		return R.ok(notice);
	}

	/**
	 * 入库
	 * 提交某一条物料数量，更新入库单已收数量或生成入库单
	 *
	 * @return R  对象
	 */
	@Operation(summary = "入库", description = "入库")
	@PostMapping("/setInBillNum")
	public R<String> setInBillNum(@RequestBody @Valid WmsInBillDetailAppDTO params) {
		String billNo = mobileReceiptService.executeLegacy(params);
		return R.ok(billNo);
	}
}
