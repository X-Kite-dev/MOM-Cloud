package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.dto.WmsLineDeliveryEvidenceDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 生产领料出库单
 */
@FeignClient(contextId = "remoteProductionPickingOutBillService", value = Constants.Name)
public interface RemoteProductionPickingOutBillService {

    @InternalCall
    @GetMapping("/wmsMaterialReceipt/internal/source/{detailId}")
    R<WmsOutBillEntity> sourceByDetailId(@PathVariable("detailId") Long detailId);

    @InternalCall
    @GetMapping("/wmsMaterialReceipt/internal/line-delivery/{detailId}")
    R<WmsLineDeliveryEvidenceDTO> lineDeliveryByDetailId(@PathVariable("detailId") Long detailId);

    /**
     * 通过id查询出库单(含明细)
     */
    @GetMapping("/produce/picking/outBill/getDeepById/{id}")
    R<WmsOutBillEntity> getDeepById(@PathVariable("id") Long id);

}
