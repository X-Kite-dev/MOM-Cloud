package com.kitex.mom.qms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.qms.Constants;
import com.kitex.mom.qms.agent.dto.QmsBatchQualityTraceDTO;
import com.kitex.mom.qms.agent.dto.QmsInspectionOverviewDTO;
import com.kitex.mom.qms.agent.dto.QmsWorkOrderQualityDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * QMS 面向智能体的受控只读查询。
 */
@FeignClient(contextId = "remoteQmsAgentQueryService", value = Constants.Name)
public interface RemoteQmsAgentQueryService {

    @InternalCall(userTokenRequired = true)
    @PostMapping("/qmsAgentQuery/internal/inspection-overview")
    R<QmsInspectionOverviewDTO.Evidence> inspectionOverview(
            @RequestBody QmsInspectionOverviewDTO.Query query);

    /**
     * 按生产工单精确读取过程和成品质量任务。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/qmsAgentQuery/internal/work-order-quality")
    R<QmsWorkOrderQualityDTO.Evidence> workOrderQuality(
            @RequestBody QmsWorkOrderQualityDTO.Query query);

    @InternalCall(userTokenRequired = true)
    @PostMapping("/qmsAgentQuery/internal/batch-quality-trace")
    R<QmsBatchQualityTraceDTO.Evidence> batchQualityTrace(
            @RequestBody QmsBatchQualityTraceDTO.Query query);
}
