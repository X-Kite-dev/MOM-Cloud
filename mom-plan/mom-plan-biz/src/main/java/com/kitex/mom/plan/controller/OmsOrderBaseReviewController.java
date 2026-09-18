package com.kitex.mom.plan.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.oms.dto.OmsOrderBaseReviewPageDto;
import com.kitex.mom.oms.dto.OmsOrderChangeReviewCommand;
import com.kitex.mom.oms.entity.OmsOrderBaseReviewEntity;
import com.kitex.mom.oms.vo.OmsOrderBaseReviewExportVo;
import com.kitex.mom.plan.service.OmsOrderBaseReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 订单评审表
 *
 * @author kitex
 * @date 2025-01-07 14:32:02
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/omsOrderBaseReview")
@Tag(description = "omsOrderBaseReview", name = "订单评审表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class OmsOrderBaseReviewController {

    private final OmsOrderBaseReviewService omsOrderBaseReviewService;

    /**
     * 订单评审分页查询
     */

    @Operation(summary = "订单评审分页查询", description = "订单评审分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseReview_view')")
    public R<Page<OmsOrderBaseReviewPageDto>> getOmsOrderBaseReviewPage(@ParameterObject Page<OmsOrderBaseReviewPageDto> page,
                                                                        @ParameterObject OmsOrderBaseReviewPageDto omsOrderBaseReview) {
        return R.ok(omsOrderBaseReviewService.getOmsOrderBaseReviewPage(page, omsOrderBaseReview));
    }

    /**
     * 查询各订单评审状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各订单评审状态的数量", description = "查询各订单评审状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject OmsOrderBaseReviewPageDto omsOrderBaseReview) {
        return R.ok(omsOrderBaseReviewService.getCount(omsOrderBaseReview));
    }

    /**
     * 通过id查询订单评审表
     *
     * @param id id
     */
    @Operation(summary = "通过id查询订单评审表", description = "通过id查询订单评审表")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseReview_view')")
    public R<OmsOrderBaseReviewEntity> getOrderReviewById(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseReviewService.getOrderReviewById(id));
    }


    /**
     * 导出excel 表格
     *
     * @param omsOrderBaseReview 查询条件
     * @param ids                导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseReview_export')")
    public List<OmsOrderBaseReviewExportVo> export(OmsOrderBaseReviewEntity omsOrderBaseReview, Long[] ids) {
        return omsOrderBaseReviewService.export(omsOrderBaseReview, ids);
    }

    /**
     * 订单评审
     *
     * @param ids        主表ids
     * @param reviewType 评审结果 0 通过 1驳回 2作废
     */
    @Operation(summary = "订单评审", description = "订单评审")
    @SysLog("订单评审")
    @GetMapping("/review")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBase_review')")
    public R<Boolean> review(@RequestParam("ids") Long[] ids,
                             @RequestParam("reviewType") String reviewType,
                             @RequestParam(value = "remark", required = false, defaultValue = "无") String remark) {
        return R.ok(omsOrderBaseReviewService.review(ids, reviewType, remark));
    }

    @Operation(summary = "受控订单变更复核", description = "批量同事务、按版本冻结事实和永久幂等请求号复核")
    @SysLog("受控订单变更复核")
    @PostMapping("/reviewChange")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBase_review')")
    public R<Boolean> reviewChange(@RequestBody OmsOrderChangeReviewCommand command) {
        return R.ok(omsOrderBaseReviewService.reviewChange(command));
    }

}
