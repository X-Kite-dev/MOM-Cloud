package com.kitex.mom.plan.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 生产计划选择精确工程版本的受控命令。 */
@Data
@Schema(description = "生产计划工程版本绑定命令")
public class PlnEngineeringReleaseBindDTO {
    /** 待绑定的生产计划ID。 */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long issueId;

    /** MDM已发布工程版本ID。 */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long releaseId;

    /** MDM冻结原文SHA256，与版本ID必须同时提交。 */
    private String contentHash;

    /** 选择或换版依据，进入排程前也必须留痕。 */
    private String reason;
}
