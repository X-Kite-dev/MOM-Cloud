package com.kitex.mom.oms.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 明确项目版本到OMS订单的精确归属；一个订单同工厂只能有一个生效项目。
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("oms_agent_project_order")
public class OmsAgentProjectOrderEntity extends BaseEntity<OmsAgentProjectOrderEntity> {

    /**
     * 实际工厂。
     */
    private String factoryCode;

    /**
     * 人工项目范围版本。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long projectId;

    /**
     * 核验通过的OMS订单精确主键，不只存可重复的展示编号。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long orderId;

    /**
     * 冻结订单号，后续改号会产生来源缺口而非猜测换绑。
     */
    private String orderCode;

    /**
     * WAITING_REVIEW、ACTIVE、REJECTED或RETIRED。
     */
    private String status;
}
