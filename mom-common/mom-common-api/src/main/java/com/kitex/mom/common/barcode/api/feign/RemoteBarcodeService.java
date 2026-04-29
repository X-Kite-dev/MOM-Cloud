package com.kitex.mom.common.barcode.api.feign;

import cn.hutool.json.JSONObject;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.Constants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 条码服务远程调用接口
 */
@FeignClient(contextId = "remoteBarcodeService", value = Constants.Name)
public interface RemoteBarcodeService {

    /**
     * 生成条码
     *
     * @param jsonObject      条码内容
     * @param barcodeRuleCode 模板编码
     * @return 条码
     */
    @PostMapping("/barcodeRule/genBarcode")
    R<String> genBarcode(@RequestBody JSONObject jsonObject, @RequestParam("barcodeRuleCode") String barcodeRuleCode);

    /**
     * 识别条码
     *
     * @param code 条码
     * @return 解析结果
     */
    @GetMapping("/barcodeRule/rander")
    R rander(@RequestParam("code") String code);

}