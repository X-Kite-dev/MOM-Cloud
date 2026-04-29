package com.kitex.mom.common.barcode.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.common.Constants;
import com.kitex.mom.common.barcode.dto.req.BarcodeRuleReq;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

/**
 * 条码模板服务远程调用接口
 */
@FeignClient(contextId = "remoteBarcodeTemplateService", value = Constants.Name)
public interface RemoteBarcodeTemplateService {

    /**
     * 分页查询模板
     */
    @GetMapping("/printTemplate/page")
    R getTemplatePage(@RequestParam(value = "current", defaultValue = "1") Integer current,
                      @RequestParam(value = "size", defaultValue = "10") Integer size,
                      @SpringQueryMap BarcodeRuleReq barcodeRuleReq);

    /**
     * 获取模板详情
     */
    @GetMapping("/printTemplate/{id}")
    R getTemplateById(@PathVariable("id") Long id);

    /**
     * 新增模板
     */
    @PostMapping("/printTemplate")
    R saveTemplate(@RequestBody BarcodeRuleReq barcodeRuleReq);

    /**
     * 修改模板
     */
    @PutMapping("/printTemplate")
    R updateTemplate(@RequestBody BarcodeRuleReq barcodeRuleReq);

    /**
     * 删除模板
     */
    @DeleteMapping("/printTemplate")
    R removeTemplate(@RequestBody Long[] ids);

}