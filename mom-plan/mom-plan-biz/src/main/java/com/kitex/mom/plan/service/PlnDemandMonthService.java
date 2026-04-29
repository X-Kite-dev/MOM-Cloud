package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.MonthPlanQueryDTO;
import com.kitex.mom.plan.dto.PlnDemandMonthBalanceDTO;
import com.kitex.mom.plan.dto.PlnDemandMonthDispatchDTO;
import com.kitex.mom.plan.entity.PlnDemandMonthEntity;
import com.kitex.mom.plan.vo.PlnDemandMonthDeviationVO;
import com.kitex.mom.plan.vo.PlnDemandMonthExcelVO;
import com.kitex.mom.plan.vo.PlnDemandMonthReadinessVO;
import com.kitex.mom.plan.vo.PlnDemandMonthVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface PlnDemandMonthService extends IService<PlnDemandMonthEntity> {

    /**
     * 导入excel 表格
     *
     * @param excelVOList   物料月需求列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    R importPlanDemandMonth(List<PlnDemandMonthExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 通过条件查询需求
     *
     * @param monthPlanQueryDTO 查询条件
     */
    List<PlnDemandMonthVO> getDemand(MonthPlanQueryDTO monthPlanQueryDTO);

    /**
     * 月需求平衡，按产品维度计算可执行需求量
     *
     * @param balanceDTO 平衡参数
     * @return 是否成功
     */
    Boolean balance(PlnDemandMonthBalanceDTO balanceDTO);

    /**
     * 月需求下推到计划下达（按日拆分）
     *
     * @param dispatchDTO 下推参数
     * @return 是否成功
     */
    Boolean dispatchToIssue(PlnDemandMonthDispatchDTO dispatchDTO);

    /**
     * 偏差分析（Forecast/Confirmed/Executed）
     *
     * @param monthPlanQueryDTO 查询条件
     * @return 偏差列表
     */
    List<PlnDemandMonthDeviationVO> deviation(MonthPlanQueryDTO monthPlanQueryDTO);

    /**
     * 月需求准备状态（是否已平衡/已下推）
     *
     * @param monthPlanQueryDTO 查询条件
     * @return 准备状态
     */
    PlnDemandMonthReadinessVO readiness(MonthPlanQueryDTO monthPlanQueryDTO);
}