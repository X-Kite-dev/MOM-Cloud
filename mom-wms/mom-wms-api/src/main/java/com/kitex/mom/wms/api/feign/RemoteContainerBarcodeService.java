package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.entity.WmsContainerBarcodeEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(contextId = "remoteContainerBarcodeService", value = Constants.Name)
public interface RemoteContainerBarcodeService {

    @GetMapping("/wmsContainerBarcode/queryBarcode")
    R<WmsContainerBarcodeEntity> queryBarcode(@RequestParam("code") String code);

}
