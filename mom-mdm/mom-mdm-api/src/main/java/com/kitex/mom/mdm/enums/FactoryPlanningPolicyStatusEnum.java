package com.kitex.mom.mdm.enums;

import java.util.Arrays;

/** 工厂计划协同策略版本状态。 */
public enum FactoryPlanningPolicyStatusEnum {
    DRAFT,
    PENDING_EFFECTIVE,
    ACTIVE,
    RETIRED,
    ACTIVATION_FAILED;

    public static boolean isValid(String code) {
        return Arrays.stream(values()).anyMatch(value -> value.name().equals(code));
    }
}
