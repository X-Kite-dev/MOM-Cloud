package com.kitex.mom.pms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.pms.Constants;
import com.kitex.mom.pms.dto.PmsAgentPurchaseCandidateQueryDTO;
import com.kitex.mom.pms.dto.PmsAgentPurchaseCommandDTO;
import com.kitex.mom.pms.dto.PmsAgentPurchaseOptionsDTO;
import com.kitex.mom.pms.dto.PmsAgentPurchaseReceiptDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(contextId = "remotePmsAgentPurchaseService", value = Constants.Name)
public interface RemotePmsAgentPurchaseService {
    @InternalCall
    @PostMapping("/pmsAgentPurchase/options")
    R<PmsAgentPurchaseOptionsDTO> options(@RequestBody PmsAgentPurchaseCandidateQueryDTO query);

    @InternalCall(userTokenRequired = true)
    @PostMapping("/pmsAgentPurchase/execute")
    R<PmsAgentPurchaseReceiptDTO> execute(@RequestBody PmsAgentPurchaseCommandDTO command);

    @InternalCall
    @GetMapping("/pmsAgentPurchase/receipt")
    R<PmsAgentPurchaseReceiptDTO> receipt(@RequestParam("requestId") String requestId,
                                          @RequestParam("mrpId") Long mrpId);

    /**
     * 包含已删除旧采购订单的固定只读永久号占用查询，必须保留实名调用身份。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/pmsAgentCommand/presence")
    R<Map<String, String>> commandPresence(@RequestBody Map<String, String> query);
}
