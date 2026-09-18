package com.kitex.mom.oms.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.Constants;
import com.kitex.mom.oms.dto.OmsDeliveryEventCommand;
import com.kitex.mom.oms.dto.OmsSalesReturnEventCommand;
import com.kitex.mom.oms.dto.OmsShipmentCorrectionEventCommand;
import com.kitex.mom.oms.dto.OmsCustomerReceiptEventCommand;
import com.kitex.mom.oms.dto.OmsCustomerExceptionResolutionCommand;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

/**
 * 查询实时库存
 */
@FeignClient(contextId = "remoteOmsOrderService", value = Constants.Name)
public interface RemoteOmsOrderService {

    @NoToken
    @PostMapping("/omsOrderBaseTrack/receiveDeliveryEvent")
    R<OmsDeliveryEventCommand.Receipt> receiveDeliveryEvent(
            @RequestBody OmsDeliveryEventCommand.Command command);

    @NoToken
    @GetMapping("/omsOrderBaseTrack/deliveryEventReceipt")
    R<OmsDeliveryEventCommand.Receipt> deliveryEventReceipt(@RequestParam("requestId") String requestId);

    @NoToken
    @GetMapping("/omsOrderBaseTrack/deliveryLineLedger")
    R<OmsDeliveryEventCommand.LineLedger> deliveryLineLedger(
            @RequestParam("orderCode") String orderCode,
            @RequestParam("detailsId") Long detailsId);

    @NoToken
    @PostMapping("/omsOrderBaseTrack/receiveSalesReturnEvent")
    R<OmsSalesReturnEventCommand.Receipt> receiveSalesReturnEvent(
            @RequestBody OmsSalesReturnEventCommand.Command command);

    @NoToken
    @GetMapping("/omsOrderBaseTrack/salesReturnEventReceipt")
    R<OmsSalesReturnEventCommand.Receipt> salesReturnEventReceipt(@RequestParam("requestId") String requestId);

    @NoToken
    @PostMapping("/omsOrderBaseTrack/receiveShipmentCorrectionEvent")
    R<OmsShipmentCorrectionEventCommand.Receipt> receiveShipmentCorrectionEvent(
            @RequestBody OmsShipmentCorrectionEventCommand.Command command);

    @NoToken
    @GetMapping("/omsOrderBaseTrack/shipmentCorrectionEventReceipt")
    R<OmsShipmentCorrectionEventCommand.Receipt> shipmentCorrectionEventReceipt(@RequestParam("requestId") String requestId);

    @NoToken
    @PostMapping("/omsOrderBaseTrack/receiveCustomerReceiptEvent")
    R<OmsCustomerReceiptEventCommand.Receipt> receiveCustomerReceiptEvent(
            @RequestBody OmsCustomerReceiptEventCommand.Command command);

    @NoToken
    @GetMapping("/omsOrderBaseTrack/customerReceiptEventReceipt")
    R<OmsCustomerReceiptEventCommand.Receipt> customerReceiptEventReceipt(@RequestParam("requestId") String requestId);

    @PostMapping("/omsOrderBaseTrack/receiveCustomerExceptionResolutionEvent")
    R<OmsCustomerExceptionResolutionCommand.Receipt> receiveCustomerExceptionResolutionEvent(
            @RequestBody OmsCustomerExceptionResolutionCommand.Command command);

    @GetMapping("/omsOrderBaseTrack/customerExceptionResolutionEventReceipt")
    R<OmsCustomerExceptionResolutionCommand.Receipt> customerExceptionResolutionEventReceipt(
            @RequestParam("requestId") String requestId);

    @GetMapping("/omsOrderBase/checkOrderBaseMsg")
    R<Boolean> checkOrderBaseMsg(@RequestParam("orderCode") String orderCode,
                                 @RequestParam("productCode") String productCode,
                                 @RequestParam("customerCode") String customerCode);

    @GetMapping("/omsOrderBase/getOrderDetails")
    R<OmsOrderDetailsEntity> getOrderDetails(@RequestParam("orderCode") String orderCode,
                                             @RequestParam("productCode") String productCode,
                                             @RequestParam("planDeliverTime") LocalDate planDeliverTime);
}
