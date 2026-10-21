package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.plan.dto.PlanShortageLocalReplanDTO;
import com.kitex.mom.plan.entity.PlnShortageReplanCaseEntity;

/**
 * 缺料场景下的局部重排领域服务。
 *
 * <p>该服务负责从缺料事实识别、有限产能候选方案生成、人工选案，到正式排程发布、取消与受控回退的
 * 完整业务闭环。它同时编排 MRP、APS、MDM 工艺投料关系和 MES 执行状态，不对应单一数据表，
 * 因此属于领域编排服务，不继承 MyBatis-Plus {@code IService}。</p>
 *
 * <p>所有写操作都必须携带业务幂等号；选案、发布和回退还必须携带对应阶段的证据指纹，防止审批期间
 * 供应、排程或现场执行事实发生变化后继续使用过期方案。</p>
 */
public interface PlnShortageLocalReplanService {

    /**
     * 分页查询缺料局部重排方案。
     *
     * <p>查询前会将超过有效期且仍处于待选状态的方案和情景标记为已过期。</p>
     *
     * @param page  分页参数
     * @param query 方案编号、计划编号、物料和状态等筛选条件；允许为空
     * @return 重排方案分页结果，包含候选方案的评分和排名摘要
     */
    Page<PlnShortageReplanCaseEntity> page(SearchPage page, PlanShortageLocalReplanDTO.Query query);

    /**
     * 查询单个候选方案及其工序级差异明细。
     *
     * @param caseId 缺料重排方案主键
     * @return 方案主信息、候选指标和工序移动明细
     */
    PlanShortageLocalReplanDTO.Proposal detail(Long caseId);

    /**
     * 查询一次缺料重排情景及其全部候选方案。
     *
     * @param scenarioId APS 多候选情景主键
     * @return 情景头和按候选排名排序的方案列表
     */
    PlanShortageLocalReplanDTO.Scenario scenario(Long scenarioId);

    /**
     * 根据可核验的缺料供应证据生成有限产能重排候选。
     *
     * <p>系统校验 MRP 缺料事实、MDM 工序投料映射、排程冻结区和 MES 可移动状态后，生成“最早恢复”、
     * “最小扰动”和“换型稳定”三个候选；相同请求号只能对应完全相同的请求内容。</p>
     *
     * @param command 候选生成命令
     * @return 已完成可行性评估和排名的情景
     */
    PlanShortageLocalReplanDTO.Scenario propose(PlanShortageLocalReplanDTO.ProposeCommand command);

    /**
     * 从同一情景中选择唯一可发布候选。
     *
     * @param command 选案命令，必须携带生成情景时的来源指纹
     * @return 完成选案后的情景和候选状态
     */
    PlanShortageLocalReplanDTO.Scenario select(PlanShortageLocalReplanDTO.SelectCommand command);

    /**
     * 将已选候选正式发布到 APS 排程并同步 MES。
     *
     * <p>发布前会重新校验供应证据、正式排程和 MES 执行指纹；MES 已同步但本地事务失败时执行补偿。</p>
     *
     * @param command 发布命令，包含方案指纹和审批冻结时的 MES 指纹
     * @return 发布状态、移动工序数量、新完工时间和 MES 发布后指纹
     */
    PlanShortageLocalReplanDTO.PublishReceipt publish(PlanShortageLocalReplanDTO.PublishCommand command);

    /**
     * 取消尚未发布的方案或整个未选案情景。
     *
     * @param command 取消命令和业务原因
     * @return 是否取消成功；重复提交相同请求号仍返回成功
     */
    boolean cancel(PlanShortageLocalReplanDTO.CancelCommand command);

    /**
     * 将已发布方案受控回退至发布前排程。
     *
     * <p>仅当 APS 与 MES 均未发生后续变更时允许回退，且回退复核人与原发布人必须分离。</p>
     *
     * @param command 回退命令，包含原方案指纹和 MES 发布后指纹
     * @return 回退状态、恢复后的完工时间和 MES 回退后指纹
     */
    PlanShortageLocalReplanDTO.PublishReceipt rollback(PlanShortageLocalReplanDTO.RollbackCommand command);
}
