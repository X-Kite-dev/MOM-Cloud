package com.kitex.mom.qms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.qms.Constants;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.dto.QmsProcedureSourceWithdrawalDTO;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
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
@FeignClient(contextId = "remoteProcedureService", value = Constants.Name)
public interface RemoteProcedureService {

    @PostMapping("/inspection/procedure/task/batchCreate")
    R<Map<String, Long>> batchCreate(@RequestBody List<InspectionProcedureCreateParam> createParam);

    @PostMapping("/inspection/procedure/task/create")
    R<Long> create(@RequestBody InspectionProcedureCreateParam createParam);

    @GetMapping("/inspection/procedure/task/getTaskByIds")
    R<List<QmsInspectionProcedureTaskEntity>> getTaskByIds(@RequestParam("ids") List<Long> ids);

    @GetMapping("/inspection/procedure/task/findDetailsByTaskId")
    R<List<QmsInspectionProcedureTaskDetailEntity>> findDetailsByTaskId(@RequestParam("taskId") Long taskId);

    @PostMapping("/inspection/procedure/task/internal/source-withdrawal")
    @NoToken
    R<QmsProcedureSourceWithdrawalDTO.Receipt> withdrawByMesSource(
            @RequestBody QmsProcedureSourceWithdrawalDTO.Command command);

}
