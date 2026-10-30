package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * SOP编制来源账；不存第二份正文或审批状态，也不充当正式节点绑定。
 */
@Data
@TenantTable
@TableName("mdm_sop_origin")
@EqualsAndHashCode(callSuper = true)
public class MdmSopOriginEntity extends BaseEntity<MdmSopOriginEntity> {
    /**
     * 当前授权工厂。
     */
    private String factoryCode;
    /**
     * 同一统一SOP版本，不能指向别的租户/工厂。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long versionId;
    /**
     * NODE_CREATE / LEGACY_COPY / LEGACY_SUPPLEMENT / DEDICATED / REVISION。
     */
    private String sourceType;
    /**
     * 发起工艺路线。
     */
    private String routeCode;
    /**
     * 发起的稳定路线节点，不是主工序编号。
     */
    private String routeNodeCode;
    /**
     * 节点主工序，仅作上下文。
     */
    private String processCode;
    /**
     * 原附件不可变快照（行被删除后依然保留）。
     */
    private String legacySnapshot;
    /**
     * 原附件转入去重键；不以文件名跨节点去重。
     */
    private String legacyKey;
    /**
     * 真正写入正式存储的原件SHA256，不推断与补传前旧文件相同。
     */
    private String originalHash;
    /**
     * 专用派生或修订所依据的精确版本。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceVersionId;
    /**
     * 创建此来源的永久业务请求号。
     */
    private String requestId;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() {
        return super.getTenantId();
    }
}
