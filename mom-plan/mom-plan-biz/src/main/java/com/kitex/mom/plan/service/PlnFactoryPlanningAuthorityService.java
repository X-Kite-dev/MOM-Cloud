package com.kitex.mom.plan.service;

import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;

/**
 * PLAN 对工厂计划权威的单次业务解析器。
 *
 * <p>实现不得使用跨请求长缓存。正式写动作必须读取 MDM 当前生效版本，避免工厂切换
 * ERP/MOM 权威后仍沿用旧授权。</p>
 */
public interface PlnFactoryPlanningAuthorityService {

    /**
     * 读取并校验当前租户、当前工厂唯一生效的计划协同策略。
     */
    MdmFactoryPlanningPolicyDTO requireCurrentPolicy(String factoryCode);

    /**
     * 再次读取当前策略并核对被冻结的版本和内容摘要。
     */
    MdmFactoryPlanningPolicyDTO revalidate(MdmFactoryPlanningPolicyDTO expected);
}
