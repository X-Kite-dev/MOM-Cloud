package com.kitex.mom.mes.vo;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 生产工单报工VO
 *
 * @author kitex
 * @date 2025-04-21 17:13:06
 */
@Data
@Accessors(chain = true)
@Schema(description = "生产工单报工VO")
public class MesTaskReportOmsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "工单编码")
    private String taskCode;

    @Schema(description = "原生产计划号；APS合批时用于区分来源")
    private String planNo;

    @Schema(description = "APS执行批次号")
    private String apsBatchNo;

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

    @Schema(description = "总需求数量")
    private BigDecimal noticeNum;

    @Schema(description = "该原生产计划在APS执行批次中的分配量")
    private BigDecimal sourceAllocatedQuantity;

    @Schema(description = "合格数量")
    private BigDecimal qualifiedNum;

    @Schema(description = "不合格品数量")
    private BigDecimal unPassNum;

    @Schema(description = "状态 新建、下达、已派工、生产中、完成")
    private String status;

    @Schema(description = "报工时间")
    private Date reportTime;
}
