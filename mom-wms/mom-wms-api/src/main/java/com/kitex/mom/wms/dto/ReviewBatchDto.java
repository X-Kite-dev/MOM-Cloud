package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class ReviewBatchDto {

    @Schema(description = "审核id集合")
    private List<Long> ids;

    @Schema(description = "审核结果  1 通过  0 不通过")
    private String type;

}
