package com.kitex.mom.qms.dto.inspection;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 提交质检结果
 *
 * @author kitex
 * @date 2022/12/5
 */
@Data
@Accessors(chain = true)
@Schema(description = "提交质检结果参数")
public class InspectionCheckResultParam {

    @Schema(description = "质检任务项ID")
    private Long detailId;

    @Schema(description = "检测结果")
    private InspectionItemResultRecord record;

}
