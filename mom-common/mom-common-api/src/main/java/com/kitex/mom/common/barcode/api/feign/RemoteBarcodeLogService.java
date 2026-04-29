package com.kitex.mom.common.barcode.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.common.Constants;
import com.kitex.mom.common.barcode.dto.req.BarcodeCheckLogReq;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

/**
 * 条码日志服务远程调用接口
 */
@FeignClient(contextId = "remoteBarcodeLogService", value = Constants.Name)
public interface RemoteBarcodeLogService {

    /**
     * 分页查询校验日志
     */
    @GetMapping("/barcodeCheckLog/page")
    R getCheckLogPage(@RequestParam(value = "current", defaultValue = "1") Integer current,
                      @RequestParam(value = "size", defaultValue = "10") Integer size,
                      @SpringQueryMap BarcodeCheckLogReq barcodeCheckLogReq);

    /**
     * 查询校验日志详情
     */
    @GetMapping("/barcodeCheckLog/{id}")
    R getCheckLogById(@PathVariable("id") Long id);

    /**
     * 删除校验日志
     */
    @DeleteMapping("/barcodeCheckLog")
    R removeCheckLog(@RequestBody Long[] ids);

}