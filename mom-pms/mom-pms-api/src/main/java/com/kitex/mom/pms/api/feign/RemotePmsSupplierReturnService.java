package com.kitex.mom.pms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.pms.Constants;
import com.kitex.mom.pms.dto.PmsSupplierReturnEvent;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * WMS 回写 PMS 采购退供实物完成事实。
 */
@FeignClient(contextId = "remotePmsSupplierReturnService", value = Constants.Name)
public interface RemotePmsSupplierReturnService {
    @PostMapping("/pmsReturn/internal/wms-completed")
    R<PmsSupplierReturnEvent.Receipt> receiveCompleted(@RequestBody PmsSupplierReturnEvent event);
}
