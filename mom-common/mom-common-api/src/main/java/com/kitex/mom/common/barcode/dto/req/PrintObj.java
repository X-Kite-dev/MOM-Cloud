package com.kitex.mom.common.barcode.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class PrintObj {

    @Schema(description = "打印设备")
    @NotEmpty(message = "设备不能为空")
    private String deviceCode;

    @NotEmpty(message = "模板不能为空")
    private String templateCode;

    @NotEmpty(message = "json数据不能为空")
    private String jsonData;

}
