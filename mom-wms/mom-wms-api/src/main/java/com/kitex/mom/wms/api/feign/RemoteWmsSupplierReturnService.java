package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.dto.WmsSupplierReturnCommand;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * PMS 调用 WMS 的采购到货后退供接口。
 */
@FeignClient(contextId = "remoteWmsSupplierReturnService", value = Constants.Name)
public interface RemoteWmsSupplierReturnService {

    @PostMapping("/supplierReturn/eligible-stocks")
    R<List<WmsSupplierReturnCommand.StockCandidate>> eligibleStocks(
            @RequestBody WmsSupplierReturnCommand.StockQuery query);

    @PostMapping("/supplierReturn/notices")
    R<WmsSupplierReturnCommand.NoticeReceipt> createNotice(
            @RequestBody WmsSupplierReturnCommand.CreateNotice command);
}
