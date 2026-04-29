package com.kitex.mom.qms.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 质检标准
 *
 * @author kyer
 * @date 2023/04/24 10:53
 **/
@Data
public class QualityStandardQueryReq {

    /**
     * 质检标准名称
     */
    @Schema(description = "质检标准名称")
    private String standardName;

    /**
     * 质检标准名称
     */
    @Schema(description = "质检类型")
    private String inspectionType;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private String published;

}
