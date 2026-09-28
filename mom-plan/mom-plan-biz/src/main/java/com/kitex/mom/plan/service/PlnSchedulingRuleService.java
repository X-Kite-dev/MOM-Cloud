package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.plan.dto.PlnSchedulingGanttDetailQueryDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnSchedulingConflictEntity;
import com.kitex.mom.plan.entity.PlnSchedulingRuleSetEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import com.kitex.mom.plan.vo.PlnSchedulingGanttDetailVO;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface PlnSchedulingRuleService extends IService<PlnSchedulingRuleSetEntity> {

    /**
     * 创建规则集
     *
     * @param ruleSet 规则集
     * @return 是否成功
     */
    Boolean createRule(PlnSchedulingRuleSetEntity ruleSet);

    /**
     * 分页查询排产任务
     *
     * @param page 分页参数
     * @return 任务分页
     */
    Page<PlnSchedulingTaskEntity> pageTask(SearchPage page, String mode, String taskNo);

    Page<PlnSchedulingTaskEntity> pageTask(SearchPage page, String mode, String taskNo, Boolean resultArchived);

    /**
     * 分页查询规则集
     *
     * @param page  分页参数
     * @param query 查询条件
     * @return 规则集分页
     */
    Page<PlnSchedulingRuleSetEntity> pageRule(SearchPage page, PlnSchedulingRuleSetEntity query);

    /**
     * 切换规则启停
     *
     * @param id          规则ID
     * @param active      是否启用
     * @param defaultRule 是否默认
     * @return 是否成功
     */
    Boolean toggleRule(Long id, Boolean active, Boolean defaultRule);

    /**
     * 更新规则集
     *
     * @param id      规则ID
     * @param ruleSet 规则内容
     * @return 是否成功
     */
    Boolean updateRule(Long id, PlnSchedulingRuleSetEntity ruleSet);

    /**
     * 删除规则集
     *
     * @param id 规则ID
     * @return 是否成功
     */
    Boolean removeRule(Long id);

    /**
     * 分页查询冲突面板
     *
     * @param page 分页参数
     * @return 冲突分页
     */
    Page<PlnSchedulingConflictEntity> pageConflict(SearchPage page, Long taskId);

    /**
     * 查询排产任务命中明细
     *
     * @param taskId 任务ID
     * @return 计划明细
     */
    List<PlnIssueEntity> listTaskDetails(Long taskId);

    Page<PlnSchedulingGanttDetailVO> queryGanttDetails(SearchPage page, PlnSchedulingGanttDetailQueryDTO query);

    /**
     * 查询已锁定计划的甘特数据（排产完成后展示用）
     */
    Page<PlnSchedulingGanttDetailVO> queryLockedGantt(SearchPage page, String productCode, String productName, LocalDate startDate, LocalDate endDate);

    /**
     * 调整排产明细分配日期。
     * scheduledEndDate 为 null 时自动按产率+工作日历计算；传入时以手动值为准。
     * 返回更新后的 { scheduledDate, scheduledEndDate }。
     */
    Map<String, LocalDate> adjustTaskDetailScheduleDate(Long taskId, Long issueId, LocalDate scheduledDate, LocalDate scheduledEndDate);
}
