package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 仓库生命周期只读依赖查询；失败不得视作无依赖。
 */
@FeignClient(contextId = "remoteMesWarehouseLifecycleService", value = Constants.Name)
public interface RemoteMesWarehouseLifecycleService {

    @InternalCall
    @GetMapping("/mesWarehouseLifecycle/references")
    R<List<String>> references(@RequestParam("warehouseCode") String warehouseCode,
                               @RequestParam("warehouseId") Long warehouseId);
}
