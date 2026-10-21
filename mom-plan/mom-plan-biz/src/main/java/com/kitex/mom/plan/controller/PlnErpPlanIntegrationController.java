package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.dto.PlnErpPlanImportCommand;
import com.kitex.mom.plan.dto.PlnErpProductionOrderReceiptCommand;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import com.kitex.mom.plan.service.PlnFormalPlanContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ERP 连接器和下游模块使用的正式计划集成端点。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/erpPlanIntegration/internal")
public class PlnErpPlanIntegrationController {

    private final PlnFormalPlanContractService contractService;

    /** ERP 连接器提交结构化正式计划；调用方必须经内部调用认证并携带租户、工厂头。 */
    @Inner
    @PostMapping("/import")
    public R<PlnFormalPlanContractDTO> importPlan(@RequestBody PlnErpPlanImportCommand command) {
        return R.ok(contractService.importErpPlan(command));
    }

    /** PLAN 内部受控流程可显式重试发布已落库的 MOM MRP 批次。 */
    @Inner
    @PostMapping("/publish-mom-mrp")
    public R<List<PlnFormalPlanContractDTO>> publishMomMrp(@RequestParam String mrpRunNo,
                                                            @RequestParam String requestId) {
        return R.ok(contractService.publishMomMrpContract(mrpRunNo, requestId));
    }

    /** ERP连接器回传生产订单号；系统核验后以新合同版本生效。 */
    @Inner
    @PostMapping("/production-order-receipt")
    public R<PlnFormalPlanContractDTO> productionOrderReceipt(
            @RequestBody PlnErpProductionOrderReceiptCommand command) {
        return R.ok(contractService.receiveErpProductionOrders(command));
    }

    /** APS、MES 或 Agent 读取当前策略下仍有效的正式合同。 */
    @Inner
    @GetMapping("/verified")
    public R<PlnFormalPlanContractDTO> verified(@RequestParam String contractNo,
                                                 @RequestParam Integer contractVersion) {
        return R.ok(contractService.getVerifiedContract(contractNo, contractVersion));
    }
}
