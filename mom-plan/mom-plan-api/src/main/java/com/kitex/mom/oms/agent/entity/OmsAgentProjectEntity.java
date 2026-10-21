package com.kitex.mom.oms.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 项目订单范围的人工权威版本，不从同客户/产品推断项目归属。
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("oms_agent_project")
public class OmsAgentProjectEntity extends BaseEntity<OmsAgentProjectEntity> {

    /**
     * 实际权限工厂。
     */
    private String factoryCode;

    /**
     * 明确人工登记的稳定项目编码。
     */
    private String projectCode;

    /**
     * 项目名称，不用于模糊推断订单。
     */
    private String projectName;

    /**
     * 范围变更必须创建新版本。
     */
    private Integer versionNo;

    /**
     * 提交永久请求号，同号异内容拒绝。
     */
    private String requestId;

    /**
     * WAITING_REVIEW、ACTIVE、REJECTED或RETIRED。
     */
    private String status;

    /**
     * 冻结精确订单主键、编号和提出依据。
     */
    private String scopeSnapshot;

    /**
     * 范围完整SHA-256指纹。
     */
    private String scopeFingerprint;

    /**
     * 提交人，不能审核自己的范围。
     */
    private String initiatedBy;

    /**
     * 异人审核生效人。
     */
    private String reviewedBy;

    /**
     * 明确提交依据/变更原因。
     */
    private String reason;
}
