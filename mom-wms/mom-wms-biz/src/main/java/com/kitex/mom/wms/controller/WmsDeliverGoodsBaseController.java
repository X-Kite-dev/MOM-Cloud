package com.kitex.mom.wms.controller;


import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.wms.entity.WmsDeliverGoodsBaseEntity;
import com.kitex.mom.wms.entity.WmsOmsDeliveryEventOutboxEntity;
import com.kitex.mom.wms.dto.WmsShipmentCustodyCommand;
import com.kitex.mom.wms.dto.WmsShipmentExceptionResolutionCommand;
import com.kitex.mom.wms.entity.WmsShipmentCustodyEntity;
import com.kitex.mom.wms.entity.WmsShipmentCustodyOperationEntity;
import com.kitex.mom.wms.entity.WmsShipmentReceiptDetailEntity;
import com.kitex.mom.wms.entity.WmsOmsCustomerReceiptOutboxEntity;
import com.kitex.mom.wms.entity.WmsShipmentExceptionResolutionEntity;
import com.kitex.mom.wms.entity.WmsOmsCustomerExceptionResolutionOutboxEntity;
import com.kitex.mom.wms.service.WmsDeliverGoodsBaseService;
import com.kitex.mom.wms.service.impl.WmsOmsDeliveryEventOutboxService;
import com.kitex.mom.wms.service.impl.WmsShipmentCustodyService;
import com.kitex.mom.wms.service.impl.WmsOmsCustomerReceiptOutboxService;
import com.kitex.mom.wms.service.impl.WmsShipmentExceptionResolutionService;
import com.kitex.common.log.annotation.SysLog;
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
 * 发货单基础表
 *
 * @author kitex
 * @date 2025-01-13 09:31:20
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsDeliverGoodsBase")
@Tag(description = "wmsDeliverGoodsBase", name = "发货单基础表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsDeliverGoodsBaseController {

    private final WmsDeliverGoodsBaseService wmsDeliverGoodsBaseService;
    private final WmsOmsDeliveryEventOutboxService omsDeliveryEventOutboxService;
    private final WmsShipmentCustodyService shipmentCustodyService;
    private final WmsOmsCustomerReceiptOutboxService customerReceiptOutboxService;
    private final WmsShipmentExceptionResolutionService exceptionResolutionService;

    @GetMapping("/custody/byDelivery/{deliveryId}")
    @PreAuthorize("@pms.hasPermission('wms_shipmentCustody_view')")
    public R<List<WmsShipmentCustodyEntity>> custodyByDelivery(@PathVariable Long deliveryId){return R.ok(shipmentCustodyService.listByDelivery(deliveryId));}
    @GetMapping("/custody/{id}/operations")
    @PreAuthorize("@pms.hasPermission('wms_shipmentCustody_view')")
    public R<List<WmsShipmentCustodyOperationEntity>> custodyOperations(@PathVariable Long id){return R.ok(shipmentCustodyService.operations(id));}
    @GetMapping("/custody/{id}/receiptDetails")
    @PreAuthorize("@pms.hasPermission('wms_shipmentCustody_view')")
    public R<List<WmsShipmentReceiptDetailEntity>> custodyReceiptDetails(@PathVariable Long id){return R.ok(shipmentCustodyService.receiptDetails(id));}
    @PostMapping("/custody/handover")
    @PreAuthorize("@pms.hasPermission('wms_shipmentCustody_handover')") @SysLog("销售发运承运交接")
    public R<WmsShipmentCustodyEntity> custodyHandover(@RequestBody WmsShipmentCustodyCommand.Handover command){return R.ok(shipmentCustodyService.handover(command));}
    @PostMapping("/custody/{id}/cancel")
    @PreAuthorize("@pms.hasPermission('wms_shipmentCustody_cancel')") @SysLog("取消销售发运承运交接")
    public R<WmsShipmentCustodyEntity> custodyCancel(@PathVariable Long id,@RequestBody WmsShipmentCustodyCommand.Cancel command){return R.ok(shipmentCustodyService.cancel(id,command));}
    @PostMapping("/custody/{id}/depart")
    @PreAuthorize("@pms.hasPermission('wms_shipmentCustody_depart')") @SysLog("销售发运门岗离场")
    public R<WmsShipmentCustodyEntity> custodyDepart(@PathVariable Long id,@RequestBody WmsShipmentCustodyCommand.Depart command){return R.ok(shipmentCustodyService.depart(id,command));}
    @PostMapping("/custody/{id}/receipt")
    @PreAuthorize("@pms.hasPermission('wms_shipmentCustody_receipt')") @SysLog("登记客户实收")
    public R<WmsShipmentCustodyEntity> custodyReceipt(@PathVariable Long id,@RequestBody WmsShipmentCustodyCommand.Receipt command){return R.ok(shipmentCustodyService.receipt(id,command));}
    @GetMapping("/custody/{id}/omsEvents")
    @PreAuthorize("@pms.hasPermission('wms_shipmentCustody_view')")
    public R<List<WmsOmsCustomerReceiptOutboxEntity>> custodyOmsEvents(@PathVariable Long id){return R.ok(customerReceiptOutboxService.listByCustody(id));}
    @PostMapping("/custody/omsEvent/{id}/retry")
    @PreAuthorize("@pms.hasPermission('wms_shipmentCustody_event_retry')") @SysLog("重试客户实收OMS事件")
    public R<WmsOmsCustomerReceiptOutboxEntity> retryCustodyOmsEvent(@PathVariable Long id){return R.ok(customerReceiptOutboxService.retry(id));}
    @PostMapping("/custody/omsEvent/{id}/reconcile")
    @PreAuthorize("@pms.hasPermission('wms_shipmentCustody_view')")
    public R<WmsOmsCustomerReceiptOutboxEntity> reconcileCustodyOmsEvent(@PathVariable Long id){return R.ok(customerReceiptOutboxService.reconcile(id));}

    @GetMapping("/custody/{id}/exceptionResolution")
    @PreAuthorize("@pms.hasPermission('wms_shipmentException_view')")
    public R<WmsShipmentExceptionResolutionEntity> exceptionResolution(@PathVariable Long id){return R.ok(exceptionResolutionService.byCustody(id));}
    @PostMapping("/custody/exceptionResolution/apply")
    @PreAuthorize("@pms.hasPermission('wms_shipmentException_apply')") @SysLog("申请客户交付异常处置")
    public R<WmsShipmentExceptionResolutionEntity> applyExceptionResolution(@RequestBody WmsShipmentExceptionResolutionCommand.Apply command){return R.ok(exceptionResolutionService.apply(command));}
    @PostMapping("/custody/exceptionResolution/{id}/approve")
    @PreAuthorize("@pms.hasPermission('wms_shipmentException_approve')") @SysLog("审批客户交付异常处置")
    public R<WmsShipmentExceptionResolutionEntity> approveExceptionResolution(@PathVariable Long id,@RequestBody WmsShipmentExceptionResolutionCommand.Decision command){return R.ok(exceptionResolutionService.approve(id,command));}
    @PostMapping("/custody/exceptionResolution/{id}/reject")
    @PreAuthorize("@pms.hasPermission('wms_shipmentException_approve')") @SysLog("驳回客户交付异常处置")
    public R<WmsShipmentExceptionResolutionEntity> rejectExceptionResolution(@PathVariable Long id,@RequestBody WmsShipmentExceptionResolutionCommand.Decision command){return R.ok(exceptionResolutionService.reject(id,command));}
    @PostMapping("/custody/exceptionResolution/{id}/settle")
    @PreAuthorize("@pms.hasPermission('wms_shipmentException_settle')") @SysLog("登记客户交付异常结算")
    public R<WmsShipmentExceptionResolutionEntity> settleExceptionResolution(@PathVariable Long id,@RequestBody WmsShipmentExceptionResolutionCommand.Settlement command){return R.ok(exceptionResolutionService.settle(id,command));}
    @PostMapping("/custody/exceptionResolution/{id}/reconcile")
    @PreAuthorize("@pms.hasPermission('wms_shipmentException_close')") @SysLog("客户交付异常结案对账")
    public R<WmsShipmentExceptionResolutionEntity> reconcileExceptionResolution(@PathVariable Long id,@RequestBody WmsShipmentExceptionResolutionCommand.Reconcile command){return R.ok(exceptionResolutionService.reconcile(id,command));}
    @GetMapping("/custody/exceptionResolution/{id}/omsEvents")
    @PreAuthorize("@pms.hasPermission('wms_shipmentException_view')")
    public R<List<WmsOmsCustomerExceptionResolutionOutboxEntity>> exceptionResolutionOmsEvents(@PathVariable Long id){return R.ok(exceptionResolutionService.omsEvents(id));}
    @PostMapping("/custody/exceptionResolution/omsEvent/{id}/retry")
    @PreAuthorize("@pms.hasPermission('wms_shipmentException_event_retry')")
    public R<WmsOmsCustomerExceptionResolutionOutboxEntity> retryExceptionResolutionOms(@PathVariable Long id){return R.ok(exceptionResolutionService.retryOms(id));}
    @PostMapping("/custody/exceptionResolution/omsEvent/{id}/reconcile")
    @PreAuthorize("@pms.hasPermission('wms_shipmentException_view')")
    public R<WmsOmsCustomerExceptionResolutionOutboxEntity> reconcileExceptionResolutionOms(@PathVariable Long id){return R.ok(exceptionResolutionService.reconcileOms(id));}

    @GetMapping("/omsEvent/byBill/{billId}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_view')")
    public R<List<WmsOmsDeliveryEventOutboxEntity>> omsEventByBill(@PathVariable Long billId) {
        return R.ok(omsDeliveryEventOutboxService.listByBill(billId));
    }

    @GetMapping("/omsEvent/byDelivery/{deliveryId}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_view')")
    public R<List<WmsOmsDeliveryEventOutboxEntity>> omsEventByDelivery(@PathVariable Long deliveryId) {
        return R.ok(omsDeliveryEventOutboxService.listByDelivery(deliveryId));
    }

    @PostMapping("/omsEvent/{id}/retry")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_create')")
    public R<WmsOmsDeliveryEventOutboxEntity> retryOmsEvent(@PathVariable Long id) {
        return R.ok(omsDeliveryEventOutboxService.retry(id));
    }

    @PostMapping("/omsEvent/{id}/reconcile")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_view')")
    public R<WmsOmsDeliveryEventOutboxEntity> reconcileOmsEvent(@PathVariable Long id) {
        return R.ok(omsDeliveryEventOutboxService.reconcile(id));
    }

    /**
     * 发货单基础表分页查询
     *
     * @param page                分页对象
     * @param wmsDeliverGoodsBase 发货单基础表
     */

    @Operation(summary = "发货单基础表分页查询", description = "分页查询发货单基础表列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_view')")
    public R<Page<WmsDeliverGoodsBaseEntity>> getWmsDeliverGoodsBasePage(@ParameterObject SearchPage page, @ParameterObject WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase) {
        return R.ok(wmsDeliverGoodsBaseService.getWmsDeliverGoodsBasePage(page.toMpPage(), wmsDeliverGoodsBase));
    }

    /**
     * 发货单基础表分页 - 统计
     */

    @Operation(summary = "发货单基础表分页 - 统计", description = "发货单基础表分页 - 统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase) {
        return R.ok(wmsDeliverGoodsBaseService.getCount(wmsDeliverGoodsBase));
    }


    /**
     * 通过ID查询发货单基础表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过ID查询发货单基础表", description = "根据ID获取发货单基础表详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_view')")
    public R<WmsDeliverGoodsBaseEntity> getDeliverBaseById(@PathVariable("id") Long id) {
        return R.ok(wmsDeliverGoodsBaseService.getDeliverBaseById(id));
    }


    /**
     * 新增发货单基础表
     */
    @Operation(summary = "新增发货单基础表", description = "添加新的发货单基础记录")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_add')")
    public R<Boolean> save(@RequestBody WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase) {
        return R.ok(wmsDeliverGoodsBaseService.saveDeliver(wmsDeliverGoodsBase));
    }

    /**
     * 发货单编辑
     */
    @PutMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_edit')")
    public R edit(@RequestBody WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase) {
        return R.ok(wmsDeliverGoodsBaseService.editDeliver(wmsDeliverGoodsBase));
    }


    /**
     * 导出excel 表格
     *
     * @param wmsDeliverGoodsBase 查询条件
     * @param ids                 导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_export')")
    public List<WmsDeliverGoodsBaseEntity> export(WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase, Long[] ids) {
        return wmsDeliverGoodsBaseService.list(Wrappers.lambdaQuery(wmsDeliverGoodsBase).in(ArrayUtil.isNotEmpty(ids), WmsDeliverGoodsBaseEntity::getId, ids));
    }

    /**
     * 发货单关闭
     */
    @Operation(summary = "关闭", description = "关闭")
    @GetMapping("/close/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_close')")
    public R close(@PathVariable("id") Long id) {
        return R.ok(wmsDeliverGoodsBaseService.close(id));
    }

    /**
     * 创建销售通知单
     */
    @Operation(summary = "创建销售通知单", description = "创建销售通知单")
    @GetMapping("/create/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_create')")
    public R create(@PathVariable("id") Long id) {
        return R.ok(wmsDeliverGoodsBaseService.create(id));
    }

    /**
     * 撤回销售通知单
     */
    @GetMapping("/retract/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_retract')")
    public R retract(@PathVariable("id") Long id) {
        return R.ok(wmsDeliverGoodsBaseService.retract(id));
    }


}
