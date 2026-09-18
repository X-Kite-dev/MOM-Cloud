package com.kitex.mom.pms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import com.kitex.mom.pms.dto.PmsAgentSupplyEvidenceDTO;
import com.kitex.mom.pms.dto.PmsAgentSupplyEvidenceQueryDTO;
import com.kitex.mom.pms.dto.PmsPlanSupplyWithdrawalDTO;
import com.kitex.mom.pms.vo.PmsPlanExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;
import java.util.Map;

public interface PmsPlanService extends IService<PmsPlanEntity> {

    /**
     * 导入excel 表格
     *
     * @param excelVOList   采购计划列表
     * @param bindingResult 错误信息列表
     * @param factoryCode   所属工厂编码
     * @return R
     */
    R importPmsPlan(List<PmsPlanExcelVO> excelVOList, BindingResult bindingResult, String factoryCode);

    /**
     * 导出excel 表格
     *
     * @param pmsPlan 查询条件
     * @param ids     导出指定ID
     * @return excel 文件流
     */
    List<PmsPlanExcelVO> export(PmsPlanEntity pmsPlan, Long[] ids);

    /**
     * 新增
     *
     * @param pmsPlan 采购计划
     */
    Boolean savePlan(PmsPlanEntity pmsPlan);

    /** 仅允许编辑尚未下单的人工计划；PLAN来源计划必须回到来源侧走受控变更。 */
    Boolean updatePlan(PmsPlanEntity pmsPlan);

    /** 仅允许删除尚未下单且无下游事实的人工计划。 */
    Boolean removePlans(List<Long> ids);


    /**
     * 通过时间和物料编码查询采购计划表
     *
     * @param templateQueryDTO 查询条件
     * @return R
     */
    List<Map<String, String>> getPlnDemand(TemporaryPlanQueryDTO templateQueryDTO);

    /**
     * 修改采购计划下单数量
     */
    void updatePlanOrderNum(List<PmsPlanEntity> pmsPlanEntityList);

    /** 采购订单驳回时幂等回退计划占用，使计划可重新下单。 */
    void rollbackPlanOrderNum(List<PmsPlanEntity> pmsPlanEntityList);

    /**
     * 处理采购计划到货数量
     */
    void updateInNoticeSave(PmsOrderDetailEntity pmsOrderDetail, Double inNum);

    /**
     * 根据采购计划下达号查询采购计划
     *
     * @param orderNo 采购计划下达号
     * @return R
     */
    List<PmsPlanEntity> omsLife(String orderNo);

    List<PmsAgentSupplyEvidenceDTO> agentSupplyEvidence(PmsAgentSupplyEvidenceQueryDTO query);

    /** 以PLAN月计划ID永久幂等创建采购/外协计划并返回精确关联。 */
    PmsPlanEntity saveFromPlanSource(PmsPlanEntity plan);

    /** 无订单、到货和供应承诺时撤回PMS供应计划；预览与执行共享冻结证据。 */
    PmsPlanSupplyWithdrawalDTO.Receipt withdrawSupplyPlan(PmsPlanSupplyWithdrawalDTO.Command command);
}
