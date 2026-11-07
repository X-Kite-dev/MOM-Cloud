package com.kitex.mom.common.barcode.api.feign;

import cn.hutool.json.JSONObject;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.Constants;
import com.kitex.mom.common.barcode.dto.FrozenBarcodeDefinition;
import com.kitex.mom.common.barcode.dto.req.BarcodeDefinitionFreezeReq;
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
	 * 以原实名令牌读取实际工厂内的条码定义，调用方须具备条码查看权限。
	 * @param request 规则编码，不接受客户端指定组织或规则内容
	 * @return 一致原定义及指纹，供业务域随执行实例持久冻结；不创建箱码
	 */
	@PostMapping("/barcodeRule/freezeDefinition")
	R<FrozenBarcodeDefinition> freezeDefinition(@RequestBody BarcodeDefinitionFreezeReq request);

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
