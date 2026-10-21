package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.dto.*;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.entity.WmsDeliverGoodsRecordEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 查询实时库存
 */
@FeignClient(contextId = "remoteDeliverService", value = Constants.Name)
public interface RemoteDeliverService {

    /**
     * 通过orderCode和明细id查询发货计划
     */
    @NoToken
    @GetMapping("/wmsDeliverGoodsPlanBase/getByOrder")
    R<List<WmsDeliverGoodsPlanBaseEntity>> getByOrder(@RequestParam("orderCode") String orderCode,
                                                      @RequestParam("detailsId") Long detailsId);

    @NoToken
    @GetMapping("/wmsDeliverGoodsPlanBase/omsDeliveryLedger")
    R<WmsOmsDeliveryLedger> omsDeliveryLedger(@RequestParam("orderCode") String orderCode,
                                              @RequestParam("detailsId") Long detailsId,
                                              @RequestParam(value = "requestId", required = false) String requestId);

    @NoToken
    @GetMapping("/wmsDeliverGoodsPlanBase/omsSalesReturnLedger")
    R<WmsOmsSalesReturnLedger> omsSalesReturnLedger(@RequestParam("orderCode") String orderCode,
                                                    @RequestParam("detailsId") Long detailsId,
                                                    @RequestParam(value = "requestId", required = false) String requestId);

    @NoToken
    @GetMapping("/wmsDeliverGoodsPlanBase/omsShipmentCorrectionLedger")
    R<WmsOmsShipmentCorrectionLedger> omsShipmentCorrectionLedger(@RequestParam("orderCode") String orderCode,
                                                                  @RequestParam("detailsId") Long detailsId,
                                                                  @RequestParam(value = "requestId", required = false) String requestId);

    @NoToken
    @GetMapping("/wmsDeliverGoodsPlanBase/omsCustomerReceiptLedger")
    R<WmsOmsCustomerReceiptLedger> omsCustomerReceiptLedger(@RequestParam("orderCode") String orderCode,
                                                            @RequestParam("detailsId") Long detailsId,
                                                            @RequestParam("requestId") String requestId);

    @GetMapping("/wmsDeliverGoodsPlanBase/omsCustomerExceptionResolutionLedger")
    R<WmsOmsCustomerExceptionResolutionLedger> omsCustomerExceptionResolutionLedger(
            @RequestParam("orderCode") String orderCode,
            @RequestParam("detailsId") Long detailsId,
            @RequestParam("requestId") String requestId);

    @NoToken
    @GetMapping("/wmsDeliverGoodsPlanBase/omsDeliverability")
    R<WmsOmsDeliverabilitySnapshot> omsDeliverability(@RequestParam("orderCode") String orderCode,
                                                      @RequestParam("detailsId") Long detailsId);

    @NoToken
    @PostMapping("/wmsDeliverGoodsPlanBase/withdrawOmsCommand")
    R<WmsOmsDeliveryWithdrawalCommand.Receipt> withdrawOmsCommand(
            @RequestBody WmsOmsDeliveryWithdrawalCommand.Command command);

    @NoToken
    @PostMapping("/wmsDeliverGoodsPlanBase/issueOmsCommand")
    R<WmsOmsDeliveryIssueCommand.Receipt> issueOmsCommand(
            @RequestBody WmsOmsDeliveryIssueCommand.Command command);

    /**
     * 根据订单号 查询所有 发货记录
     */
    @GetMapping("/wmsDeliverGoodsRecord/getDeliverRecordListByOrder")
    R<List<WmsDeliverGoodsRecordEntity>> getDeliverRecordListByOrder(@RequestParam("orderCode") String orderCode);

    /**
     * 根据发货计划 查询所有 发货记录
     */
    @GetMapping("/wmsDeliverGoodsRecord/getDeliverRecordListByPlan")
    R<List<WmsDeliverGoodsRecordEntity>> getDeliverRecordListByPlan(@RequestParam("planCodeList") List<String> planCodeList);

}
