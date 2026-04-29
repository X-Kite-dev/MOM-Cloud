package com.kitex.mom.qms.dto.inspection;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 追加质检任务参数
 *
 * @author kitex
 * @date 2022/12/2
 */
@Data
@Accessors(chain = true)
@Schema(description = "追加质检任务参数")
public class InspectionAppendParam {

    @Schema(description = "任务id")
    private Long id;

    @Schema(description = "数量")
    private Integer num;

}
