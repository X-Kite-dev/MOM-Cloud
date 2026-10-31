package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.Constants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 计划业务对仓库的历史依赖，查询失败不得作为无依赖。
 */
@FeignClient(contextId = "remotePlnWarehouseLifecycleService", value = Constants.Name)
public interface RemotePlnWarehouseLifecycleService {

    @InternalCall
    @GetMapping("/plnWarehouseLifecycle/references")
    R<List<String>> references(@RequestParam("warehouseCode") String warehouseCode);
}
