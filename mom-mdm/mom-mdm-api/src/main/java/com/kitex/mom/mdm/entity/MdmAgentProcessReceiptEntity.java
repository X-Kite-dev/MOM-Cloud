package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * AI工艺建议转交真实标准的永久请求/回执账，重试不重复创建版本。
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mdm_agent_process_receipt")
public class MdmAgentProcessReceiptEntity extends BaseEntity<MdmAgentProcessReceiptEntity> {
    /**
     * 当前实际工厂。
     */
    private String factoryCode;
    /**
     * 来源提案编号，不接受模型自行生成新业务范围。
     */
    private String proposalNo;
    /**
     * 永久业务请求号。
     */
    private String requestId;
    /**
     * CREATE_DRAFT或RELEASE；正式发布仍复用领域异人状态门。
     */
    private String operationType;
    /**
     * 完整命令指纹，同号异载荷拒绝。
     */
    private String payloadHash;
    /**
     * 来源已发布工艺标准。
     */
    private Long sourceStandardId;
    /**
     * 实际创建/发布的正式领域标准主键。
     */
    private Long targetStandardId;
    /**
     * 不可覆盖的领域执行回执。
     */
    private String receiptSnapshot;
}
