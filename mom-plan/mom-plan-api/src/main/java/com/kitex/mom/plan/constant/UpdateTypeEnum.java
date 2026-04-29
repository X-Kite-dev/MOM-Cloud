package com.kitex.mom.plan.constant;

import lombok.Getter;

/**
 * @author kitex
 * @date 2025-02-26 10:18:59
 */
@Getter
public enum UpdateTypeEnum {
    NUM_ADD("num_add", "数量修改"),
    NUM_UPDATE("num_update", "数量修改"),
    STATUS_UPDATE("status_update", "状态变更");

    private final String code;
    private final String desc;

    UpdateTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

}
