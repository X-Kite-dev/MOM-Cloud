package com.kitex.mom.plan.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 受控Agent命令的永久领域意图与原始业务回执。
 * 意图先以独立事务保存，失联只能查原账，不能以“未找到AI成功状态”再次下发。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("plan_agent_command_proof")
@EqualsAndHashCode(callSuper = true)
public class PlanAgentCommandProofEntity extends BaseEntity<PlanAgentCommandProofEntity> {
    /** 实际授权工厂，不允许请求头跳过组织隔离。 */
    private String factoryCode;
    /** 执行器编码；同一个永久请求号不能改用于其他业务动作。 */
    private String toolCode;
    /** APS或执行偏差局部重排等固定命令契约分支。 */
    private String commandVariant;
    /** 首次命令永久号，失败、回滚和恢复均不得更换。 */
    private String requestId;
    /** 服务端已反序列化命令的规范化SHA-256指纹。 */
    private String payloadHash;
    /** 原始完整命令快照，不包含模型生成的任意SQL。 */
    private String commandSnapshot;
    /** IN_FLIGHT、UNCERTAIN、CONFIRMED；未知不等于没有执行。 */
    private String status;
    /** 原业务服务返回的永久回执，仅确认后写入且不能改写。 */
    private String receiptSnapshot;
    /** 创建领域执行意图的实名岗位。 */
    private String actorCode;
    /** 失联或失败诊断摘要，不包含凭证。 */
    private String failureReason;
}
