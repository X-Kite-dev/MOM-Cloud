package com.kitex.mom.plan.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper=true) @TenantTable @TableName("pln_objective_template_operation")
public class PlnObjectiveTemplateOperation extends BaseEntity<PlnObjectiveTemplateOperation> {
    private String factoryCode;
    private Long templateId;
    private String requestId;
    private String fingerprint;
    private String action;
    private String resultStatus;
    private Long operatorId;
    private String reason;
}
