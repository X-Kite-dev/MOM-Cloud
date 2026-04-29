package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PurchaseQcStateEnum {

    WAIT_QC("0", "待质检"),
    PASS("1", "质检通过"),
    REJECT("2", "质检驳回");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (PurchaseQcStateEnum state : PurchaseQcStateEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
