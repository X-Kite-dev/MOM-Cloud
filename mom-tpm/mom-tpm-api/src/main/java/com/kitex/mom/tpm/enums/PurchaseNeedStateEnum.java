package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PurchaseNeedStateEnum {

    WAIT_COMMIT("0", "待提交"),
    WAIT_APPROVAL("1", "待审核"),
    PASS("2", "已通过"),
    REJECT("3", "已驳回"),
    CREATE("4", "已下发");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (PurchaseNeedStateEnum state : PurchaseNeedStateEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
