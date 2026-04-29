package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnDemandEntity;
import com.kitex.mom.plan.vo.PlanDemandExcelVO;
import com.kitex.mom.plan.vo.PlnDemandVO;
import org.springframework.validation.BindingResult;

import java.util.List;
import java.util.Map;

public interface PlnDemandService extends IService<PlnDemandEntity> {

    /**
     * 通过条件查询需求
     *
     * @param templateQueryDTO 查询条件
     */
    List<PlnDemandVO> getDemand(TemporaryPlanQueryDTO templateQueryDTO);

    /**
     * 导入excel 表格
     *
     * @param excelVOList   物料需求列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    R importPlanDemand(List<PlanDemandExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 查询T+N页面生产情况
     *
     * @param templateQueryDTO 查询条件
     */
    List<Map<String, String>> getPlanMes(TemporaryPlanQueryDTO templateQueryDTO);
}