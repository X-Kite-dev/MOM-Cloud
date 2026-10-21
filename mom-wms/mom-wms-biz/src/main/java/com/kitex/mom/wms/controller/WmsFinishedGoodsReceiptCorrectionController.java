package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionCommand;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionView;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionEntity;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionOperationEntity;
import com.kitex.mom.wms.service.WmsFinishedGoodsReceiptCorrectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 成品实收入库红冲申请、异岗复核、履历和MES/QMS回执续跑。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/finishedGoodsReceiptCorrection")
@Tag(name = "成品实收入库受控红冲")
public class WmsFinishedGoodsReceiptCorrectionController {
    private final WmsFinishedGoodsReceiptCorrectionService service;

    @GetMapping("/page")
    @Operation(summary = "红冲申请分页")
    @PreAuthorize("@pms.hasPermission('wms_bill_view_in_product')")
    public R<Page<WmsFinishedGoodsReceiptCorrectionEntity>> page(@ParameterObject SearchPage page,
                                                                 @ParameterObject WmsFinishedGoodsReceiptCorrectionEntity query) {
        return R.ok(service.page(page, query));
    }

    @GetMapping("/preview/{noticeId}")
    @Operation(summary = "按成品入库通知核验红冲资格和当前精确库存")
    @PreAuthorize("@pms.hasPermission('wms_bill_view_in_product')")
    public R<WmsFinishedGoodsReceiptCorrectionView> preview(@PathVariable Long noticeId) {
        return R.ok(service.preview(noticeId));
    }

    @GetMapping("/{correctionId}")
    @Operation(summary = "红冲申请、冻结库存与当前漂移详情")
    @PreAuthorize("@pms.hasPermission('wms_bill_view_in_product')")
    public R<WmsFinishedGoodsReceiptCorrectionView> detail(@PathVariable Long correctionId) {
        return R.ok(service.detail(correctionId));
    }

    @PostMapping("/apply")
    @Operation(summary = "提交整张MES成品实收入库红冲申请")
    @SysLog("提交成品实收入库红冲申请")
    @PreAuthorize("@pms.hasPermission('wms_bill_add_in_product')")
    public R<WmsFinishedGoodsReceiptCorrectionEntity> apply(
            @RequestBody WmsFinishedGoodsReceiptCorrectionCommand command) {
        return R.ok(service.apply(command));
    }

    @PostMapping("/{correctionId}/withdraw")
    @Operation(summary = "原申请人撤回待复核红冲申请")
    @SysLog("撤回成品实收入库红冲申请")
    @PreAuthorize("@pms.hasPermission('wms_bill_add_in_product')")
    public R<WmsFinishedGoodsReceiptCorrectionEntity> withdraw(@PathVariable Long correctionId,
                                                               @RequestBody WmsFinishedGoodsReceiptCorrectionCommand command) {
        return R.ok(service.withdraw(correctionId, command));
    }

    @PostMapping("/{correctionId}/reject")
    @Operation(summary = "异岗驳回红冲申请")
    @SysLog("驳回成品实收入库红冲申请")
    @PreAuthorize("@pms.hasPermission('wms_bill_review_in_product')")
    public R<WmsFinishedGoodsReceiptCorrectionEntity> reject(@PathVariable Long correctionId,
                                                             @RequestBody WmsFinishedGoodsReceiptCorrectionCommand command) {
        return R.ok(service.reject(correctionId, command));
    }

    @PostMapping("/{correctionId}/approve")
    @Operation(summary = "异岗批准并原子执行实物红冲")
    @SysLog("批准执行成品实收入库红冲")
    @PreAuthorize("@pms.hasPermission('wms_bill_review_in_product')")
    public R<WmsFinishedGoodsReceiptCorrectionEntity> approve(@PathVariable Long correctionId) {
        return R.ok(service.approve(correctionId));
    }

    @GetMapping("/{correctionId}/operations")
    @Operation(summary = "红冲不可变操作履历")
    @PreAuthorize("@pms.hasPermission('wms_bill_view_in_product')")
    public R<List<WmsFinishedGoodsReceiptCorrectionOperationEntity>> operations(
            @PathVariable Long correctionId) {
        return R.ok(service.operations(correctionId));
    }

    @PostMapping("/{correctionId}/retry")
    @Operation(summary = "人工续投尚未被MES或QMS永久确认的红冲事件")
    @SysLog("续投成品实物红冲跨服务事件")
    @PreAuthorize("@pms.hasPermission('wms_bill_review_in_product')")
    public R<Integer> retry(@PathVariable Long correctionId) {
        return R.ok(service.retry(correctionId));
    }
}
