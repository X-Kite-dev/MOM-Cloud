package com.kitex.mom.qms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.Constants;
import com.kitex.mom.qms.dto.inspection.InspectionPurchaseCreateParam;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 采购feign
 */
@FeignClient(contextId = "remotePurchaseService", value = Constants.Name)
public interface RemotePurchaseService {

    @PostMapping("/inspection/purchase/task/batchCreate")
    R<Map<String, Long>> batchCreate(@RequestBody List<InspectionPurchaseCreateParam> createParam);

    @GetMapping("/inspection/purchase/task/getTaskByMatCodeAndSupplierCodeAndBatchNo")
    R<QmsInspectionPurchaseTaskEntity> getTaskByMatCodeAndSupplierCodeAndBatchNo(@RequestParam("matCode") String matCode,
                                                                                 @RequestParam("supplierCode") String supplierCode,
                                                                                 @RequestParam("batchNo") String batchNo);

    @GetMapping("/inspection/purchase/task/findDetailsByTaskId")
    R<List<QmsInspectionPurchaseTaskDetailEntity>> findDetailsByTaskId(@RequestParam("taskId") Long taskId);
}
