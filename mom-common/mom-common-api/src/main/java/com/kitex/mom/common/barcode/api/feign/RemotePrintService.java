package com.kitex.mom.common.barcode.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.common.Constants;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.dto.req.PrintObj;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 预览打印远程调用接口
 */
@FeignClient(contextId = "remotePrintService", value = Constants.Name)
public interface RemotePrintService {

    /**
     * 打印
     *
     * @param obj 打印数据
     */
    @PostMapping("/print/print")
    R<String> print(@RequestBody PrintObj obj);

    /**
     * 预览
     *
     * @param obj 预览编码
     */
    @PostMapping("/print/preview")
    R<String> preview(@RequestBody PreviewObj obj);

    /**
     * 生成二维码
     *
     * @param data 数据
     */
    @GetMapping("/print/createQrInputStream")
    R<byte[]> createQrInputStream(@RequestParam String data);

}