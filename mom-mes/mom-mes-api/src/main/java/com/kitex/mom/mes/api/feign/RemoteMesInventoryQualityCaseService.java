package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.entity.MesInventoryQualityCaseEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(contextId = "remoteMesInventoryQualityCaseService", value = Constants.Name)
public interface RemoteMesInventoryQualityCaseService {
    @InternalCall
    @org.springframework.web.bind.annotation.PostMapping("/mesInventoryQualityCase/internal/receipt-read")
    R<com.kitex.mom.mes.dto.MesCaseReceiptBundle> readReceipts(@org.springframework.web.bind.annotation.RequestBody com.kitex.mom.mes.dto.MesCaseReceiptRead request);

    @InternalCall(userTokenRequired = true)
    @GetMapping("/mesInventoryQualityCase/internal/receipt-chain/{caseId}/{ncrId}/{detailId}/{decisionKey}")
    R<com.kitex.mom.mes.dto.MesCaseReceiptBundle> chain(@PathVariable("caseId") Long caseId, @PathVariable("ncrId") Long ncrId, @PathVariable("detailId") Long detailId, @PathVariable("decisionKey") String key);

    @InternalCall(userTokenRequired = true)
    @GetMapping("/mesInventoryQualityCase/internal/{id}")
    R<MesInventoryQualityCaseEntity> source(@PathVariable("id") Long id);

    @InternalCall(userTokenRequired = true)
    @org.springframework.web.bind.annotation.PostMapping("/mesInventoryQualityCase/internal/disposition")
    R<com.kitex.mom.mes.entity.MesCaseDispositionReceiptEntity> execute(@org.springframework.web.bind.annotation.RequestBody com.kitex.mom.mes.dto.MesCaseDispositionCommand command);

    @InternalCall(userTokenRequired = true)
    @GetMapping("/mesInventoryQualityCase/internal/disposition/{detailId}/{decisionKey}")
    R<com.kitex.mom.mes.entity.MesCaseDispositionReceiptEntity> latest(@PathVariable("detailId") Long detailId, @PathVariable("decisionKey") String decisionKey);
}
