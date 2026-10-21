package com.kitex.mom.oms.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 项目归属异人审核/退役不可变操作账。
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("oms_agent_project_review")
public class OmsAgentProjectReviewEntity extends BaseEntity<OmsAgentProjectReviewEntity> {

    /**
     * 实际工厂。
     */
    private String factoryCode;

    /**
     * 被审核版本主键。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long projectId;

    /**
     * 永久操作请求号。
     */
    private String requestId;

    /**
     * APPROVE、REJECT或RETIRE。
     */
    private String operationType;

    /**
     * 原命令摘要，阻止同号异命令。
     */
    private String payloadHash;

    /**
     * 实名操作人。
     */
    private String actorCode;

    /**
     * 审核/退役理由。
     */
    private String remark;

    /**
     * 完整人工证据引用和精确命令，不作为模型授权输入。
     */
    private String evidenceSnapshot;
}
