package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum BillOperateTypeEnum {

    IN("in", "入库"),
    OUT("out", "出库");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (BillOperateTypeEnum state : BillOperateTypeEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
