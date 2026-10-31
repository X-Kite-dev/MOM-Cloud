package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionSubmissionDTO;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionCommand;
import com.kitex.mom.wms.dto.app.WmsMobileFinishedGoodsCorrectionDTO;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionEntity;
import com.kitex.mom.wms.service.WmsFinishedGoodsCorrectionMobileAccess;
import com.kitex.mom.wms.service.WmsFinishedGoodsCorrectionSubmissionService;
import com.kitex.mom.wms.service.WmsFinishedGoodsReceiptCorrectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 手机端只提交成品纠错申请和核对原登记，不开放管理端审核或实物冲减。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/finishedGoodsReceiptCorrection")
public class AppFinishedGoodsReceiptCorrectionController {

	private static final String ADD = "@pms.hasPermission('wms_bill_add_in_product', 'wms_bill_add_in_product_*')";
	private final WmsFinishedGoodsReceiptCorrectionService service;
	private final WmsFinishedGoodsCorrectionMobileAccess mobile;
	private final WmsFinishedGoodsCorrectionSubmissionService submissions;

	@PostMapping("/context")
	@PreAuthorize(ADD)
	public R<WmsMobileFinishedGoodsCorrectionDTO.Context> context(@RequestBody WmsMobileFinishedGoodsCorrectionDTO.ContextQuery query) {
		return R.ok(mobile.context(query));
	}

	/**
	 * 原SUBMIT履历由业务事务保存，避免在通用日志重复输出质量授权码。
	 */
	@PostMapping("/apply")
	@PreAuthorize(ADD)
	public R<WmsFinishedGoodsReceiptCorrectionEntity> apply(@RequestBody WmsFinishedGoodsReceiptCorrectionCommand command) {
		return R.ok(service.applyMobile(command));
	}

	/**
	 * 只读核对沿原授权，关闭手机配置后也不重新探测新作业能力。
	 */
	@PostMapping("/submissions/lookup")
	public R<WmsFinishedGoodsCorrectionSubmissionDTO.Receipt> lookup(@RequestBody WmsFinishedGoodsCorrectionSubmissionDTO.Query query) {
		return R.ok(submissions.lookup(query));
	}
}
