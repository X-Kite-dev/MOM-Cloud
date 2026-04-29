package com.kitex.mom.common.barcode.dto.req;

import cn.hutool.json.JSON;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
public class PrintTemplateObj implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "模板json")
    private JSON template;

    @Schema(description = "数据json")
    private JSON printData;
}
