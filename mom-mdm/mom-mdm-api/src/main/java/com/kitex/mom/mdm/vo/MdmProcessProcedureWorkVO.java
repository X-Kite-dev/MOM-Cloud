package com.kitex.mom.mdm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 工艺路线默认工作中心回显VO
 */
@Data
@Schema(description = "工艺路线默认工作中心回显VO")
public class MdmProcessProcedureWorkVO {
    /**
     * 工位编码
     */
    @Schema(description = "工位编码")
    private String workCode;
    /**
     * 工位名称
     */
    @Schema(description = "工位名称")
    private String workName;
    /**
     * 线体编码
     */
    @Schema(description = "线体编码")
    private String lineCode;
    /**
     * 线体名称
     */
    @Schema(description = "线体名称")
    private String lineName;
}