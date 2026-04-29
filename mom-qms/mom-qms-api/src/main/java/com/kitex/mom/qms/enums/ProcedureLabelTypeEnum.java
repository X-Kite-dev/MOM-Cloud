package com.kitex.mom.qms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ProcedureLabelTypeEnum {

    FIRST("首检"),
    MIDDLE("中检"),
    LAST("末检");

    private final String name;


}
