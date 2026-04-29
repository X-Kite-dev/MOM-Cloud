package com.kitex.mom.pms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.pms.Constants;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptInNumDTO;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptRejectDTO;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptSetQualifiedNumDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 采购收料单
 */
@FeignClient(contextId = "remotePurchaseReceiptService", value = Constants.Name)
public interface RemotePurchaseReceiptService {

    /**
     * 质检回馈合格数量
     */
    @PostMapping("/wmsPurchaseReceipt/setQualifiedNum")
    R<Boolean> setQualifiedNum(@RequestBody WmsPurchaseReceiptSetQualifiedNumDTO body);

    /**
     * 入库时更新采购收料单
     */
    @PutMapping("/wmsPurchaseReceipt/addMatNum")
    R<Boolean> addMatNum(@RequestBody WmsPurchaseReceiptInNumDTO wmsPurchaseReceiptInNumDTO);

    /**
     * 采购入库通知单作废后回调
     */
    @PostMapping("/wmsPurchaseReceipt/voidObjsBack")
    R<Boolean> voidObjsBack(@RequestBody WmsPurchaseReceiptRejectDTO wmsPurchaseReceiptRejectDTO);

}
