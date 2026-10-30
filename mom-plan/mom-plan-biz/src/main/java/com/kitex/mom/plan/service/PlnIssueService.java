package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.dto.MonthPlanQueryDTO;
import com.kitex.mom.plan.dto.PlnEngineeringReleaseBindDTO;
import com.kitex.mom.plan.dto.PlnIssueModuleDTO;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnIssueFeedbackEntity;
import com.kitex.mom.plan.vo.PlnIssueMonthVO;
import com.kitex.mom.plan.vo.PlnIssueVO;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface PlnIssueService extends MPJDeepService<PlnIssueEntity> {

    /**
     * 新增或修改计划下达任务
     *
     * @param plnIssueVO 计划下达VO
     * @return R
     */
    Boolean saveOrUpdatePlnIssue(PlnIssueVO plnIssueVO);

    /**
     * 兼容旧通知入口：只允许创建独立的未下发计划，不接受客户端状态/来源编号。
     */
    Boolean createStandaloneIssue(PlnIssueEntity issue);

    /**
     * 通过条件查询计划下达
     *
     * @param templateQueryDTO 查询条件
     */
    Map<String, List<PlnIssueVO>> getIssueDate(TemporaryPlanQueryDTO templateQueryDTO);

    /**
     * 下发到各模块
     *
     * @param plnIssueModuleDTO 生产计划下发DTO
     * @return R
     */
    Boolean module(PlnIssueModuleDTO plnIssueModuleDTO);

    /**
     * 查询单张生产计划当前产品可选择的已发布工程版本。
     */
    List<MdmRouteEngineeringDTO.ProductionCandidate> engineeringCandidates(Long issueId);

    /**
     * 在排程、合批或下达前绑定精确工程版本对并留下变更日志。
     */
    PlnIssueEntity bindEngineeringRelease(PlnEngineeringReleaseBindDTO command);

    /**
     * 主生产计划
     *
     * @param page           分页对象
     * @param plnIssueEntity 查询对象
     * @return R
     */
    Page<PlnIssueEntity> getMesPlanPage(Page page, PlnIssueEntity plnIssueEntity);

    /**
     * 通过id删除计划下达表
     *
     * @param ids id列表
     * @return R
     */
    Boolean removeBatchIssue(List<Long> ids);

    /**
     * 计划下达反馈
     *
     * @param plnFeedbackEntities 计划下达反馈实体列表
     * @return R
     */
    Boolean feedback(List<PlnIssueFeedbackEntity> plnFeedbackEntities);

    /**
     * 通过id查询计划下达表
     *
     * @param id id
     * @return R
     */
    PlnIssueEntity getIssueDetail(Long id);

    /**
     * 通过条件查询上个月生产计划下达
     *
     * @param monthPlanQueryDTO 查询条件
     */
    List<Map<String, Object>> getIssueUpMonth(MonthPlanQueryDTO monthPlanQueryDTO);

    /**
     * 查询生产排产列表
     *
     * @param temporaryPlanQueryDTO 查询条件
     */
    List<Map<Object, Object>> getMesPlan(TemporaryPlanQueryDTO temporaryPlanQueryDTO);

    /**
     * 生产计划排产
     *
     * @param plnIssueMonthVO 月计划下达VO
     * @return R
     */
    Boolean mesSaveOrUpdate(PlnIssueMonthVO plnIssueMonthVO);

    /**
     * 主生产计划日期
     *
     * @param productCodes 产品编码列表
     * @return R
     */
    List<Map<Object, Object>> getMesPlanDetails(String[] productCodes);

    /**
     * 修改计划下达表
     *
     * @param plnIssue 计划下达表
     * @return R
     */
    Boolean updateIssue(PlnIssueEntity plnIssue);

    /**
     * 修改生产生产排产结果表
     *
     * @param omsOrderLifeCycleDtoList 订单生命周期DTO
     * @return R
     */
    Boolean edit(List<OmsOrderLifeCycleDTO> omsOrderLifeCycleDtoList);

    /**
     * 根据产品编码和下达日期查询列表
     *
     * @param productCode 产品编码
     * @param issueTime   下达时间
     * @param status      状态
     * @return R
     */
    List<PlnIssueEntity> getIssueListByDate(String productCode, LocalDate issueDate, String status);
}
