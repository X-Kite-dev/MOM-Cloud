package com.kitex.mom.pms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.pms.Constants;
import com.kitex.mom.pms.dto.PmsPlanProcessBackDTO;
import com.kitex.mom.pms.dto.PmsAgentSupplyEvidenceDTO;
import com.kitex.mom.pms.dto.PmsAgentSupplyEvidenceQueryDTO;
import com.kitex.mom.pms.dto.PmsPlanSupplyWithdrawalDTO;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(contextId = "remotePmsPlanService", value = Constants.Name)
public interface RemotePmsPlanService {

    /**
     * 新增采购计划表
     *
     * @param pmsPlan 采购计划表
     * @return R
     */
    @PostMapping("/pmsPlan")
    R<Boolean> save(@RequestBody PmsPlanEntity pmsPlan);

    /**
     * 审核-流程结束修改状态
     *
     * @param backDTO 参数
     */
    @PostMapping("/pmsPlan/processBackChangeState")
    R<Boolean> processBackChangeState(@RequestBody PmsPlanProcessBackDTO backDTO);

    /**
     * 根据采购计划下达号查询采购计划
     *
     * @param orderNo 采购计划下达号
     * @return R
     */
    @GetMapping("/pmsPlan/omsLife")
    R<List<PmsPlanEntity>> omsLife(@RequestParam("orderNo") String orderNo);

    @PostMapping("/pmsPlan/agent/supply-evidence")
    R<List<PmsAgentSupplyEvidenceDTO>> agentSupplyEvidence(@RequestBody PmsAgentSupplyEvidenceQueryDTO query);

    @PostMapping("/pmsPlan/internal/source-plan")
    R<PmsPlanEntity> saveFromPlanSource(@RequestBody PmsPlanEntity plan);

    @PostMapping("/pmsPlan/internal/supply-withdrawal")
    R<PmsPlanSupplyWithdrawalDTO.Receipt> withdrawSupplyPlan(@RequestBody PmsPlanSupplyWithdrawalDTO.Command command);

}
