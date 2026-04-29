package com.kitex.mom.oms.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.Constants;
import com.kitex.mom.oms.dto.OmsUpdateOrderDeliverDto;
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

    /**
     * 根据产品编码集合 查询实时库存
     */
    @PostMapping("/omsOrderBaseTrack/updateOrderDeliver")
    R<Boolean> updateOrderDeliver(@RequestBody List<OmsUpdateOrderDeliverDto> deliverDtoList);

    @GetMapping("/omsOrderBase/checkOrderBaseMsg")
    R<Boolean> checkOrderBaseMsg(@RequestParam("orderCode") String orderCode,
                                 @RequestParam("productCode") String productCode,
                                 @RequestParam("customerCode") String customerCode);

    @GetMapping("/omsOrderBase/getOrderDetails")
    R<OmsOrderDetailsEntity> getOrderDetails(@RequestParam("orderCode") String orderCode,
                                             @RequestParam("productCode") String productCode,
                                             @RequestParam("planDeliverTime") LocalDate planDeliverTime);
}
