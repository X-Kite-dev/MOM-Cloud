package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.dto.MesPickingWmsOutBillBackDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 生产领料单
 */
@FeignClient(contextId = "remoteWarehousePickingBillService", value = Constants.Name)
public interface RemoteWarehousePickingBillService {

    /**
     * 领料出库单完成后返回结果
     */
    @PostMapping("/mesWarehousePickingBill/wmsOutBillBack")
    R<Boolean> wmsOutBillBack(@RequestBody MesPickingWmsOutBillBackDTO dto);
}
