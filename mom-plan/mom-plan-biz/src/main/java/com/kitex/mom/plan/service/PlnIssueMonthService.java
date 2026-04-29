package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.dto.MonthPlanQueryDTO;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnIssueMonthEntity;
import com.kitex.mom.plan.vo.PlnIssueMonthVO;

import java.util.List;
import java.util.Map;

public interface PlnIssueMonthService extends IService<PlnIssueMonthEntity> {

    /**
     * 新增或修改月计划下达表
     *
     * @param plnIssueMonthVO 月计划下达VO
     * @return R
     */
    Boolean saveOrUpdateIssueMonth(PlnIssueMonthVO plnIssueMonthVO);

    /**
     * 按月统计计划下达数据
     *
     * @param monthPlanQueryDTO 查询条件
     */
    Map<String, List<PlnIssueMonthVO>> getIssueMonth(MonthPlanQueryDTO monthPlanQueryDTO);

    /**
     * T+N 月计划
     *
     * @param templateQueryDTO 查询条件
     */
    List<Map<Object, Object>> temporary(TemporaryPlanQueryDTO templateQueryDTO);

    /**
     * 下发到各模块
     *
     * @param ids 计划下达ids
     * @return R
     */
    Boolean module(Long[] ids);

    /**
     * 修改月计划下达表
     *
     * @param plnIssueMonth 月计划下达表
     * @return R
     */
    Boolean updateIssueMonth(PlnIssueMonthEntity plnIssueMonth);

    /**
     * 修改采购计划下达表
     *
     * @param plnIssueMonthEntityList 采购计划下达列表
     * @return R
     */
    Boolean edit(List<PlnIssueMonthEntity> plnIssueMonthEntityList);
}