package com.kitex.mom.mdm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

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

    @Schema(description = "是否主工位")
    private Boolean primary;

    @Schema(description = "工序在工位上的选择优先级")
    private Integer priority;

    @Schema(description = "工位标准工时（分钟）")
    private BigDecimal standardWorkingTime;

    @Schema(description = "工位产能（件/小时）")
    private BigDecimal stationCapacity;

    @Schema(description = "工位并行资源槽位数")
    private Integer parallelCapacity;

    @Schema(description = "跨工位共享资源组编码")
    private String resourceGroupCode;

    @Schema(description = "跨工位共享资源组名称")
    private String resourceGroupName;

    @Schema(description = "资源组共享并行容量")
    private Integer resourceGroupCapacity;

    @Schema(description = "工位绑定设备编码")
    private String[] deviceCodes;

    @Schema(description = "工位绑定设备名称")
    private String[] deviceNames;

    @Schema(description = "工位绑定员工编码")
    private String[] employeeCodes;

    @Schema(description = "工位绑定员工姓名")
    private String[] employeeNames;
}
