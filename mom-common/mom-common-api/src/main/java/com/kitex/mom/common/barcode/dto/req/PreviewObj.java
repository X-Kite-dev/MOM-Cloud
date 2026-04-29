package com.kitex.mom.common.barcode.dto.req;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class PreviewObj {

    @NotEmpty(message = "模板不能为空")
    private String templateCode;

    @NotEmpty(message = "json数据不能为空")
    private String jsonData;

}
