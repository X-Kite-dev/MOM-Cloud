package com.kitex.mom.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class OrderToMesDto {

    @Schema(description = "工单编码")
    private String taskCode;

    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "规格型号")
    private String matSpec;

    @Schema(description = "工序名称")
    private String routeName;

    @Schema(description = "工序编码")
    private String routeCode;

    @Schema(description = "工位编码")
    private String worksiteCodes;

    @Schema(description = "工位名称")
    private String worksiteNames;

    @Schema(description = "车间编码")
    private String workshopCode;

    @Schema(description = "车间名称")
    private String workshopName;

    @Schema(description = "需求数量")
    private BigDecimal noticeNum;

    @Schema(description = "合格数量")
    private BigDecimal qualifiedNum;

    @Schema(description = "报工时间")
    private Date reportTime;

    @Schema(description = "状态 新建、下达、已派工、生产中、完成")
    private String status;

}
