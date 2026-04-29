package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 生产报工-投料
 *
 * @author kitex
 * @since 2025-05-13
 */
@Data
public class MesTaskReportFeedVO {

    @Schema(description = "派工单号")
    private String arrangeCode;

    @Schema(description = "任务单号")
    private String produceTaskCode;

    @Schema(description = "生产工单号")
    private String taskCode;

    @Schema(description = "员工工号")
    private String workerCode;

    @Schema(description = "员工姓名")
    private String workerName;

    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "规格型号")
    private String matSpec;

    @Schema(description = "报工数量")
    private BigDecimal noticeNum;

    @Schema(description = "投料数量")
    private BigDecimal feedNum;

    @Schema(description = "报工时间")
    private Date reportTime;

    @Schema(description = "条码/流转码")
    private String transferCode;

    @Schema(description = "质检任务id")
    private Long inspectionTaskId;

    @Schema(description = "质检结果")
    private String inspectionResult;

    @Schema(description = "工厂编码")
    private String factoryCode;

}
