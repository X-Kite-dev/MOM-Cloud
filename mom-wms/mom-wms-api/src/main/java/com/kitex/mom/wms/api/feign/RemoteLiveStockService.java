package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.dto.*;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 查询实时库存
 */
@FeignClient(contextId = "remoteLiveStockService", value = Constants.Name)
public interface RemoteLiveStockService {

    /**
     * 根据产品编码集合 查询实时库存
     */
    @GetMapping("/material/wmsLiveStock/getLiveStockByProductCodeList")
    R<List<WmsLiveStockEntity>> getLiveStockByProductCodeList(@SpringQueryMap WmsLiveStockByProductCodeDto w);

    /**
     * 根据产品编码集合 查询实时库存
     */
    @GetMapping("/material/wmsLiveStock/getLiveStockList")
    R<List<WmsLiveStockEntity>> getLiveStockList(@SpringQueryMap WmsLiveStockQueryDTO wmsLiveStock);

    @InternalCall
    @PostMapping("/material/wmsLiveStock/quality/status")
    R<WmsQualityStatusResult> applyQualityStatus(@RequestBody WmsQualityStatusCommand command);

    @PostMapping("/material/wmsLiveStock/quality/execute")
    R<WmsQualityStatusResult> executeQualityAction(@RequestBody WmsQualityActionExecuteCommand command);


}
