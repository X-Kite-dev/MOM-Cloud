package com.kitex.mom.qms.api.feign.factory;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.api.feign.RemotePurchaseService;
import com.kitex.mom.qms.dto.inspection.InspectionPurchaseCreateParam;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 采购feign
 */
@Slf4j
@Component
public class RemotePurchaseServiceFallbackFactory implements FallbackFactory<RemotePurchaseService> {

    @Override
    public RemotePurchaseService create(Throwable cause) {
        return new RemotePurchaseService() {
            @Override
            public R<Map<String, Long>> batchCreate(List<InspectionPurchaseCreateParam> createParam) {
                log.error(cause.getMessage());
                return R.failed(cause.getMessage());
            }

            @Override
            public R<QmsInspectionPurchaseTaskEntity> getTaskByMatCodeAndSupplierCodeAndBatchNo(String matCode, String supplierCode, String batchNo) {
                log.error(cause.getMessage());
                return R.failed(cause.getMessage());
            }

            @Override
            public R<List<QmsInspectionPurchaseTaskDetailEntity>> findDetailsByTaskId(Long taskId) {
                log.error(cause.getMessage());
                return R.failed(cause.getMessage());
            }
        };
    }
}
