package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 新工厂计划策略初始化默认值。
 *
 * <p>该记录只用于创建新 FACTORY 节点时生成草稿，不是运行时权威，不能被 PLAN 或 Agent 直接消费。</p>
 */
@Data
@TenantTable
@TableName("mdm_tenant_planning_default")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "租户新工厂计划协同草稿默认值")
public class MdmTenantPlanningDefaultEntity extends BaseEntity<MdmTenantPlanningDefaultEntity> {
    private String mrpAuthority;
    private String erpSystemCode;
    private String productionOrderSyncMode;
    private String substituteGovernanceMode;
    private Boolean enabled;
}
