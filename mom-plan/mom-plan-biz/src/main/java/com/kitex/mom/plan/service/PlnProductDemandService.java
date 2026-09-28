package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.vo.PlnProductDemandExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;
import java.util.Map;

/**
 * 计划接收表服务类
 */
public interface PlnProductDemandService extends IService<PlnProductDemandEntity> {

    /**
     * 导出
     *
     * @param planMesPlan
     * @param ids
     * @return
     */
    List<PlnProductDemandExcelVO> exportExcel(PlnProductDemandEntity planMesPlan, Long[] ids);

    /**
     * 计划接收
     *
     * @param planIds
     * @return
     */
    Boolean receive(Long[] planIds);

    /**
     * 新增生产计划
     *
     * @param planMesPlan
     * @return
     */
    Boolean savePlan(PlnProductDemandEntity planMesPlan);

    /**
     * 人工新增需求只能创建无外部来源的未接收草案。
     */
    Boolean saveManualPlan(PlnProductDemandEntity planMesPlan);

    /**
     * 更新未接收需求并复核需求仓库主数据。
     */
    Boolean updatePlan(PlnProductDemandEntity planMesPlan);

    /**
     * 仅供OMS在未接收、无下游事实时同步其权威订单需求。
     */
    Boolean updateFromOms(PlnProductDemandEntity planMesPlan);

    /**
     * 旧删除入口只能逐条删除无MRP/APS/MES事实的未接收草案。
     */
    Boolean deleteDrafts(Long[] ids);

    /**
     * OMS撤回其他模块前可先校验产品需求能否安全删除。
     */
    void assertDraftDeletable(Long id);

    /**
     * OMS订单变更评审预检：仅未接收草案或已接收但没有MRP/APS/MES事实的同源需求。
     */
    void assertOmsReviewChangeable(Long id, String orderCode, Long orderDetailId);

    /**
     * 仅由OMS异岗版本评审事务调用；冻结原数量/交期并保持需求接收状态。
     */
    PlnProductDemandEntity applyOmsReviewedChange(Long id, String orderCode, Long orderDetailId,
                                                  Long orderChangeVersionId,
                                                  java.math.BigDecimal beforeQuantity, java.time.LocalDate beforeDate,
                                                  java.math.BigDecimal afterQuantity, java.time.LocalDate afterDate);

    /**
     * 通过条件查询需求日期
     *
     * @param templateQueryDTO 查询条件
     */
    List<Map<String, Object>> getDemandDate(TemporaryPlanQueryDTO templateQueryDTO);

    /**
     * 导入客户订单excel 表
     *
     * @param excelVOList   对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    R importExcel(List<PlnProductDemandExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * mrp运算
     *
     * @param ids 计划ids
     * @return
     */
    R mrp(Long[] ids);
}
