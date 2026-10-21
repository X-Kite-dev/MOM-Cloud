package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.plan.dto.PlnIssueChangeDTO;
import com.kitex.mom.plan.entity.PlnIssueChangeEntity;
import com.kitex.mom.plan.entity.PlnIssueChangeOperationEntity;

import java.util.List;

/** 已排产/已下达生产计划受控变更服务。 */
public interface PlnIssueChangeService extends IService<PlnIssueChangeEntity> {
    /** 冻结跨模块证据并提交异岗复核。 */
    PlnIssueChangeEntity submit(PlnIssueChangeDTO.SubmitCommand command);

    /** 异岗复核；批准后立即启动固定顺序的跨模块撤回与 MRP 重算。 */
    PlnIssueChangeEntity review(PlnIssueChangeDTO.ReviewCommand command);

    /** 复用永久下游命令，继续执行失败或部分完成的变更单。 */
    PlnIssueChangeEntity retry(PlnIssueChangeDTO.RetryCommand command);

    /** 由原申请人撤回尚未复核的申请。 */
    PlnIssueChangeEntity withdraw(PlnIssueChangeDTO.WithdrawCommand command);

    /** 查询当前工厂有权访问的变更详情。 */
    PlnIssueChangeEntity detail(Long id);

    /** 查询变更单的不可变人工操作履历。 */
    List<PlnIssueChangeOperationEntity> operations(Long id);

    /** 按生产计划、状态和当前工厂分页查询工作台数据。 */
    Page<PlnIssueChangeEntity> page(SearchPage page, Long issueId, String status);
}
