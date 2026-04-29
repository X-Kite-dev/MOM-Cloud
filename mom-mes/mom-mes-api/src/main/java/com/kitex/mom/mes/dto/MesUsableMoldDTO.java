package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class MesUsableMoldDTO {

    private String moldCode;

    private String moldName;
}
