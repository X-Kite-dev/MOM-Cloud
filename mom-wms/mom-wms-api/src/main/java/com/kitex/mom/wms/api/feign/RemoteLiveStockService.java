package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.dto.WmsLiveStockByProductCodeDto;
import com.kitex.mom.wms.dto.WmsLiveStockQueryDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;

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


}
