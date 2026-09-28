package com.kitex.mom.plan.controller;


import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.oms.dto.*;
import com.kitex.mom.oms.entity.*;
import com.kitex.mom.oms.vo.OmsFulfillmentRiskWorkbenchVO;
import com.kitex.mom.oms.vo.OmsLifeVO;
import com.kitex.mom.oms.vo.OmsOrderDetailsVO;
import com.kitex.mom.plan.service.OmsFulfillmentSnapshotService;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import com.kitex.mom.plan.service.impl.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 订单跟踪表
 *
 * @author kitex
 * @date 2025-01-07 14:36:05
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/omsOrderBaseTrack")
@Tag(description = "omsOrderBaseTrack", name = "订单跟踪表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class OmsOrderBaseTrackController {

    private final OmsOrderBaseTrackService omsOrderBaseTrackService;
    private final OmsOrderChangeLedgerService changeLedger;
    private final OmsDeliveryWithdrawalService deliveryWithdrawalService;
    private final OmsDeliveryIssueService deliveryIssueService;
    private final OmsDeliveryEventService deliveryEventService;
    private final OmsSalesReturnEventService salesReturnEventService;
    private final OmsShipmentCorrectionEventService shipmentCorrectionEventService;
    private final OmsCustomerReceiptEventService customerReceiptEventService;
    private final OmsCustomerExceptionResolutionEventService customerExceptionResolutionEventService;
    private final OmsFulfillmentRiskService fulfillmentRiskService;
    private final OmsFulfillmentRiskAutomationService fulfillmentRiskAutomationService;
    private final OmsFulfillmentSnapshotService fulfillmentSnapshotService;

    /**
     * 订单跟踪表分页查询
     */

    @Operation(summary = "订单跟踪表分页查询", description = "订单跟踪表分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<Page<OmsOrderBaseTrackPageDto>> getOmsOrderBaseTrackPage(@ParameterObject SearchPage page,
                                                                      @ParameterObject OmsOrderBaseTrackPageDto omsOrderBaseTrack) {
        return R.ok(omsOrderBaseTrackService.getOmsOrderBaseTrackPage(page.toMpPage(), omsOrderBaseTrack));
    }

    /**
     * 订单总览分页查询
     */

    @Operation(summary = "订单总览分页查询", description = "订单总览分页查询")
    @GetMapping("/overviewPage")
    public R<Page<OmsOrderOverviewDto>> getOverviewPage(@ParameterObject SearchPage page,
                                                        @ParameterObject OmsOrderOverviewDto omsOrderOverviewDto) {
        return R.ok(omsOrderBaseTrackService.getOverviewPage(page.toMpPage(), omsOrderOverviewDto));
    }

    /**
     * 订单总览日期列表
     */

    @Operation(summary = "订单总览日期列表", description = "订单总览日期列表")
    @GetMapping("/overviewList")
    public R<List<Map<String, Object>>> getOverviewList(@ParameterObject String[] productCodes, @ParameterObject LocalDate date,
                                                        @ParameterObject Long factoryCode) {
        return R.ok(omsOrderBaseTrackService.getOverviewList(productCodes, date));
    }

    /**
     * 订单总览日期详情
     */
    @Operation(summary = "订单总览日期列表", description = "订单总览日期列表")
    @GetMapping("/overviewDetails")
    public R<List<OmsOrderDetailsVO>> getOverviewDetails(@ParameterObject String productCode, @ParameterObject LocalDate date) {
        return R.ok(omsOrderBaseTrackService.getOverviewDetails(productCode, date));
    }

    /**
     * 查询各订单跟踪状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各订单跟踪状态的数量", description = "查询各订单跟踪状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject OmsOrderBaseTrackPageDto omsOrderBaseTrack) {
        return R.ok(omsOrderBaseTrackService.getCount(omsOrderBaseTrack));
    }

    /**
     * 通过id查询订单跟踪表
     *
     * @param id id
     */
    @Operation(summary = "通过id查询订单跟踪表", description = "通过id查询订单跟踪表")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<OmsOrderBaseTrackEntity> getOrderTrackById(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseTrackService.getOrderTrackById(id));
    }

    /**
     * 通过id查询订单跟踪表
     *
     * @param id id -->明细id
     */
    @Operation(summary = "通过id查询订单跟踪表(生产、发货)-明细id", description = "通过id查询订单跟踪表(生产、发货)-明细id")
    @GetMapping("/details/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<OmsOrderBaseTrackEntity> getOrderTrackDetailsById(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseTrackService.getOrderTrackDetailsById(id));
    }


    /**
     * 根据id 和 日期 更新日历信息
     */
    @Operation(summary = "根据id 和 日期 更新日历信息", description = "根据id 和 日期 更新日历信息")
    @GetMapping("/getCalendarMsg")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<OmsOrderBaseTrackEntity> getCalendarMsg(@RequestParam("id") Long id,
                                                     @RequestParam("date") String date) {
        return R.ok(omsOrderBaseTrackService.getCalendarMsg(id, date));
    }

    /**
     * 导出excel 表格
     *
     * @param omsOrderBaseTrack 查询条件
     * @param ids               导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_export')")
    public List<OmsOrderBaseTrackEntity> export(OmsOrderBaseTrackEntity omsOrderBaseTrack, Long[] ids) {
        return omsOrderBaseTrackService.list(Wrappers.lambdaQuery(omsOrderBaseTrack).in(ArrayUtil.isNotEmpty(ids), OmsOrderBaseTrackEntity::getId, ids));
    }

    /**
     * 订单变更
     */
    @Operation(summary = "订单变更", description = "订单变更")
    @SysLog("订单变更")
    @PostMapping("/change")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_review')")
    public R<Boolean> change(@RequestBody OmsOrderBaseTrackEntity omsOrderBaseTrack) {
        return R.ok(omsOrderBaseTrackService.change(omsOrderBaseTrack));
    }

    @Operation(summary = "订单变更版本履历")
    @GetMapping("/changeVersions")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<List<OmsOrderChangeVersionEntity>> changeVersions(@RequestParam String orderCode,
                                                               @RequestParam String factoryCode) {
        return R.ok(changeLedger.versions(orderCode, factoryCode));
    }

    @Operation(summary = "订单变更不可变操作履历")
    @GetMapping("/changeVersions/{id}/operations")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<List<OmsOrderChangeOperationEntity>> changeOperations(@PathVariable Long id) {
        return R.ok(changeLedger.operations(id));
    }

    @Operation(summary = "订单变更写入PLAN需求的逐行回执")
    @GetMapping("/changeVersions/{id}/planRevisions")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<List<OmsOrderPlanRevisionEntity>> changePlanRevisions(@PathVariable Long id) {
        return R.ok(changeLedger.planRevisions(id));
    }


    /**
     * 订单跟踪下发
     */
    @Operation(summary = "订单跟踪下发", description = "订单跟踪下发")
    @SysLog("订单跟踪下发")
    @PostMapping("/send/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_send')")
    public R<OmsDeliveryIssueCommand.Receipt> send(@PathVariable("id") Long id,
                                                   @RequestBody OmsDeliveryIssueCommand.Submit command) {
        return R.ok(deliveryIssueService.submit(id, "ALL", command));
    }

    /**
     * 订单跟踪下发-计划
     */
    @Operation(summary = "订单跟踪下发-计划", description = "订单跟踪下发-计划")
    @PostMapping("/send/plan/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_send_plan')")
    public R<Boolean> sendPlan(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseTrackService.sendPlan(id));
    }

    /**
     * 订单跟踪下发-发货
     */
    @Operation(summary = "订单跟踪下发-发货", description = "订单跟踪下发-发货")
    @PostMapping("/send/deliver/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_send_deliver')")
    public R<OmsDeliveryIssueCommand.Receipt> sendDeliver(@PathVariable("id") Long id,
                                                          @RequestBody OmsDeliveryIssueCommand.Submit command) {
        return R.ok(deliveryIssueService.submit(id, "DELIVER", command));
    }

    @PostMapping("/send/retry/all/{requestId}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_send')")
    public R<OmsDeliveryIssueCommand.Receipt> retryAllIssue(@PathVariable String requestId) {
        return R.ok(deliveryIssueService.retry(requestId, "ALL"));
    }

    @PostMapping("/send/retry/deliver/{requestId}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_send_deliver')")
    public R<OmsDeliveryIssueCommand.Receipt> retryDeliverIssue(@PathVariable String requestId) {
        return R.ok(deliveryIssueService.retry(requestId, "DELIVER"));
    }

    @GetMapping("/send/history")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<List<OmsDeliveryIssueCommand.History>> issueHistory(@RequestParam String orderCode) {
        return R.ok(deliveryIssueService.list(orderCode));
    }

    /**
     * 订单跟踪 - 撤回
     */
    @Operation(summary = "订单跟踪 - 撤回", description = "订单跟踪 - 撤回")
    @PostMapping("/withdraw/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_send_withdraw')")
    public R<OmsDeliveryWithdrawalCommand.Receipt> withdraw(@PathVariable("id") Long id,
                                                            @RequestBody OmsDeliveryWithdrawalCommand.Submit command) {
        return R.ok(deliveryWithdrawalService.submit(id, command));
    }

    @PostMapping("/withdraw/retry/{requestId}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_send_withdraw')")
    public R<OmsDeliveryWithdrawalCommand.Receipt> retryWithdrawal(@PathVariable String requestId) {
        return R.ok(deliveryWithdrawalService.retry(requestId));
    }

    @GetMapping("/withdraw/history")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<List<OmsDeliveryWithdrawalCommand.History>> withdrawalHistory(@RequestParam String orderCode) {
        return R.ok(deliveryWithdrawalService.list(orderCode));
    }

    @Inner
    @PostMapping("/receiveDeliveryEvent")
    public R<OmsDeliveryEventCommand.Receipt> receiveDeliveryEvent(
            @RequestBody OmsDeliveryEventCommand.Command command) {
        return R.ok(deliveryEventService.receive(command));
    }

    @Inner
    @GetMapping("/deliveryEventReceipt")
    public R<OmsDeliveryEventCommand.Receipt> deliveryEventReceipt(@RequestParam String requestId) {
        return R.ok(deliveryEventService.findReceipt(requestId));
    }

    @Inner
    @GetMapping("/deliveryLineLedger")
    public R<OmsDeliveryEventCommand.LineLedger> deliveryLineLedger(
            @RequestParam("orderCode") String orderCode, @RequestParam("detailsId") Long detailsId) {
        return R.ok(deliveryEventService.lineLedger(orderCode, detailsId));
    }

    @Inner
    @PostMapping("/receiveSalesReturnEvent")
    public R<OmsSalesReturnEventCommand.Receipt> receiveSalesReturnEvent(
            @RequestBody OmsSalesReturnEventCommand.Command command) {
        return R.ok(salesReturnEventService.receive(command));
    }

    @Inner
    @GetMapping("/salesReturnEventReceipt")
    public R<OmsSalesReturnEventCommand.Receipt> salesReturnEventReceipt(@RequestParam String requestId) {
        return R.ok(salesReturnEventService.findReceipt(requestId));
    }

    @Inner
    @PostMapping("/receiveShipmentCorrectionEvent")
    public R<OmsShipmentCorrectionEventCommand.Receipt> receiveShipmentCorrectionEvent(
            @RequestBody OmsShipmentCorrectionEventCommand.Command command) {
        return R.ok(shipmentCorrectionEventService.receive(command));
    }

    @Inner
    @GetMapping("/shipmentCorrectionEventReceipt")
    public R<OmsShipmentCorrectionEventCommand.Receipt> shipmentCorrectionEventReceipt(@RequestParam String requestId) {
        return R.ok(shipmentCorrectionEventService.findReceipt(requestId));
    }

    @Inner
    @PostMapping("/receiveCustomerReceiptEvent")
    public R<OmsCustomerReceiptEventCommand.Receipt> receiveCustomerReceiptEvent(
            @RequestBody OmsCustomerReceiptEventCommand.Command command) {
        return R.ok(customerReceiptEventService.receive(command));
    }

    @Inner
    @GetMapping("/customerReceiptEventReceipt")
    public R<OmsCustomerReceiptEventCommand.Receipt> customerReceiptEventReceipt(@RequestParam String requestId) {
        return R.ok(customerReceiptEventService.findReceipt(requestId));
    }

    @Inner
    @PostMapping("/receiveCustomerExceptionResolutionEvent")
    public R<OmsCustomerExceptionResolutionCommand.Receipt> receiveCustomerExceptionResolutionEvent(
            @RequestBody OmsCustomerExceptionResolutionCommand.Command command) {
        return R.ok(customerExceptionResolutionEventService.receive(command));
    }

    @Inner
    @GetMapping("/customerExceptionResolutionEventReceipt")
    public R<OmsCustomerExceptionResolutionCommand.Receipt> customerExceptionResolutionEventReceipt(
            @RequestParam String requestId) {
        return R.ok(customerExceptionResolutionEventService.findReceipt(requestId));
    }

    /**
     * 订单生命周期
     *
     * @param id id -->明细id
     */
    @Operation(summary = "订单生命周期", description = "订单生命周期")
    @GetMapping("/life/{id}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<OmsLifeVO> lifePlan(@PathVariable("id") Long id) {
        return R.ok(omsOrderBaseTrackService.lifePlan(id));
    }

    @Operation(summary = "冻结订单交付行履约承诺快照")
    @PostMapping("/fulfillmentSnapshots/{orderDetailId}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_snapshot_capture')")
    public R<OmsFulfillmentSnapshotEntity> captureFulfillmentSnapshot(@PathVariable Long orderDetailId,
                                                                      @RequestBody OmsFulfillmentSnapshotCommand.Capture command) {
        return R.ok(fulfillmentSnapshotService.capture(orderDetailId, command));
    }

    @Operation(summary = "订单交付行履约承诺快照历史")
    @GetMapping("/fulfillmentSnapshots/{orderDetailId}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<List<OmsFulfillmentSnapshotEntity>> fulfillmentSnapshots(@PathVariable Long orderDetailId) {
        return R.ok(fulfillmentSnapshotService.list(orderDetailId));
    }

    @Operation(summary = "履约承诺快照完整冻结证据")
    @GetMapping("/fulfillmentSnapshots/detail/{snapshotId}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<OmsFulfillmentSnapshotEntity> fulfillmentSnapshotDetail(@PathVariable Long snapshotId) {
        return R.ok(fulfillmentSnapshotService.detail(snapshotId));
    }

    @Operation(summary = "全工厂订单履约风险工作台")
    @GetMapping("/fulfillmentRisks/page")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<OmsFulfillmentRiskWorkbenchVO> fulfillmentRiskWorkbench(@ParameterObject SearchPage page,
                                                                     @ParameterObject OmsFulfillmentRiskQuery query) {
        return R.ok(fulfillmentRiskService.workbench(page.toMpPage(), query));
    }

    @Operation(summary = "履约风险自动扫描与催办运行概览")
    @GetMapping("/fulfillmentRisks/automation/overview")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<Map<String, Object>> fulfillmentRiskAutomationOverview() {
        return R.ok(fulfillmentRiskAutomationService.overview());
    }

    @Operation(summary = "履约风险催办发件箱分页")
    @GetMapping("/fulfillmentRisks/automation/notices/page")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<Page<OmsFulfillmentRiskNoticeEntity>> fulfillmentRiskNoticePage(@ParameterObject SearchPage page,
                                                                             @RequestParam(required = false) String status, @RequestParam(required = false) String orderCode) {
        return R.ok(fulfillmentRiskAutomationService.noticePage(page, status, orderCode));
    }

    @Operation(summary = "履约风险催办结果人工核对")
    @PostMapping("/fulfillmentRisks/automation/notices/resolve")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_risk_manage')")
    public R<OmsFulfillmentRiskNoticeEntity> resolveFulfillmentRiskNotice(
            @RequestBody OmsFulfillmentRiskAutomationCommand.NoticeResolve command) {
        return R.ok(fulfillmentRiskAutomationService.resolve(command));
    }

    @Operation(summary = "订单交付行履约风险案件")
    @GetMapping("/fulfillmentRisks/{orderDetailId}")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<List<OmsFulfillmentRiskCaseEntity>> fulfillmentRisks(@PathVariable Long orderDetailId) {
        return R.ok(fulfillmentRiskService.list(orderDetailId));
    }

    @Operation(summary = "以当前跨域事实同步履约风险")
    @PostMapping("/fulfillmentRisks/{orderDetailId}/sync")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_risk_sync')")
    public R<List<OmsFulfillmentRiskCaseEntity>> syncFulfillmentRisks(@PathVariable Long orderDetailId,
                                                                      @RequestBody OmsFulfillmentRiskCommand.Sync command) {
        return R.ok(fulfillmentRiskService.sync(orderDetailId, command));
    }

    @Operation(summary = "履约风险责任分派")
    @PostMapping("/fulfillmentRisks/cases/{caseId}/assign")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_risk_manage')")
    public R<OmsFulfillmentRiskCaseEntity> assignFulfillmentRisk(@PathVariable Long caseId,
                                                                 @RequestBody OmsFulfillmentRiskCommand.Assign command) {
        return R.ok(fulfillmentRiskService.assign(caseId, command));
    }

    @Operation(summary = "履约风险责任人提交处置进展")
    @PostMapping("/fulfillmentRisks/cases/{caseId}/progress")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_risk_handle')")
    public R<OmsFulfillmentRiskCaseEntity> progressFulfillmentRisk(@PathVariable Long caseId,
                                                                   @RequestBody OmsFulfillmentRiskCommand.Progress command) {
        return R.ok(fulfillmentRiskService.progress(caseId, command));
    }

    @Operation(summary = "履约风险异岗事实复核关闭")
    @PostMapping("/fulfillmentRisks/cases/{caseId}/close")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_risk_close')")
    public R<OmsFulfillmentRiskCaseEntity> closeFulfillmentRisk(@PathVariable Long caseId,
                                                                @RequestBody OmsFulfillmentRiskCommand.Close command) {
        return R.ok(fulfillmentRiskService.close(caseId, command));
    }

    @Operation(summary = "履约风险不可变操作履历")
    @GetMapping("/fulfillmentRisks/cases/{caseId}/operations")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<List<OmsFulfillmentRiskOperationEntity>> fulfillmentRiskOperations(@PathVariable Long caseId) {
        return R.ok(fulfillmentRiskService.operations(caseId));
    }

}
