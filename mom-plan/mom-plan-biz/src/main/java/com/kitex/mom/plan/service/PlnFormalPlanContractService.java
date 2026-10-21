package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.dto.PlnErpPlanImportCommand;
import com.kitex.mom.plan.dto.PlnErpProductionOrderReceiptCommand;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import com.kitex.mom.plan.entity.PlnFormalPlanContractEntity;

import java.util.List;

/**
 * ERP/MOM 统一正式计划合同服务。
 *
 * <p>继承 {@link IService} 只为遵循项目服务规范；控制器不会暴露通用新增、修改和删除接口，
 * 正式合同只能从明确的领域方法产生。</p>
 */
public interface PlnFormalPlanContractService extends IService<PlnFormalPlanContractEntity> {

    /** 核验并幂等接收 ERP 权威计划。 */
    PlnFormalPlanContractDTO importErpPlan(PlnErpPlanImportCommand command);

    /** 将已经落库并通过权威复核的 MOM MRP 批次发布为统一正式合同。 */
    List<PlnFormalPlanContractDTO> publishMomMrpContract(String mrpRunNo, String requestId);

    /** 核验 ERP 建单回执并生成带 ERP 生产订单号的正式合同新版本。 */
    PlnFormalPlanContractDTO receiveErpProductionOrders(PlnErpProductionOrderReceiptCommand command);

    /** 读取当前工厂可用于 APS/MES 的已核验合同。 */
    PlnFormalPlanContractDTO getVerifiedContract(String contractNo, Integer contractVersion);
}
