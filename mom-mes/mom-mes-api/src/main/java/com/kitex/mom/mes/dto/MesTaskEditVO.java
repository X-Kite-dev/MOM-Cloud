package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 生产工单编辑
 */
@Data
public class MesTaskEditVO {

    @Schema(description = "工单编码")
    private String code;

    @Schema(description = "工单类型 成品计划 零件计划")
    private String type;

    @Schema(description = "生产计划单号")
    private String planNo;

    @Schema(description = "生产订单号")
    private String productionOrder;

    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "规格型号")
    private String matSpec;

    @Schema(description = "计划开始时间")
    private Date planStartDate;

    @Schema(description = "计划结束时间")
    private Date planEndDate;

    @Schema(description = "总需求数量")
    private BigDecimal noticeNum;

    @Schema(description = "日需求数量")
    private BigDecimal dayNeedNum;

    @Schema(description = "已排产数量")
    private BigDecimal plannedNum;

    @Schema(description = "完成数量")
    private BigDecimal completeNum;

    @Schema(description = "成品入库量")
    private BigDecimal inStockNum;

    @Schema(description = "不合格品数量")
    private BigDecimal unPassNum;

    @Schema(description = "已下达数量")
    private BigDecimal alreadyNoticeNum;

    @Schema(description = "开始时间")
    private Date startTime;

    @Schema(description = "完成时间")
    private Date finishTime;

    @Schema(description = "状态 新建、下达、已派工、生产中、完成")
    private String status;

    @Schema(description = "bomId")
    private String bomId;

    @Schema(description = "bom快照")
    private String bomSnapshot;

    @Schema(description = "bom版本")
    private String bomVersion;


}
