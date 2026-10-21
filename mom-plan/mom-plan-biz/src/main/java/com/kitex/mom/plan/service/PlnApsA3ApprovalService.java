package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.dto.PlnApsDecisionDTO;
import com.kitex.mom.plan.entity.PlnApsA3ApprovalEntity;

/** PLAN 自有的 APS A3 送审、异岗审核和发布凭证服务。 */
public interface PlnApsA3ApprovalService extends IService<PlnApsA3ApprovalEntity> {

    /** 查询当前案件最近一条审批记录；案件访问范围由服务端校验。 */
    PlnApsDecisionDTO.Approval current(Long caseId);

    /** 计划员冻结 A2 候选及证据并送审。 */
    PlnApsDecisionDTO.Approval submit(Long caseId, PlnApsDecisionDTO.ApprovalSubmitCommand command);

    /** 与申请人和 A2 选案人不同的实名账号审批或驳回。 */
    PlnApsDecisionDTO.Approval decide(Long caseId, PlnApsDecisionDTO.ApprovalDecisionCommand command);

    /** 发布事务内重新核验审批、身份与冻结证据。 */
    PlnApsA3ApprovalEntity requireApproved(Long caseId, PlnApsDecisionDTO.PublishCommand command,
                                            boolean replay);

    /** 正式发布成功后，在同一事务内将审批标记为已消费。 */
    void consume(PlnApsA3ApprovalEntity approval);
}
