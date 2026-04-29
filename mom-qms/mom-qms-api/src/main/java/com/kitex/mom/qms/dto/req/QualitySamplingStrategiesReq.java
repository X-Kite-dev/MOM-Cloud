package com.kitex.mom.qms.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 抽样策略查询
 *
 * @author kyer
 * @date 2023/04/24 10:17
 **/
@Data
public class QualitySamplingStrategiesReq {

    /**
     * AQL最大值
     */
    @Schema(description = "AQL最大值")
    private Double aqlMax;

    /**
     * AQL最小值
     */
    @Schema(description = "AQL最小值")
    private Double aqlMin;

    /**
     * 检测水平
     */
    @Schema(description = "检测水平")
    private String inspectionLevel;

}
