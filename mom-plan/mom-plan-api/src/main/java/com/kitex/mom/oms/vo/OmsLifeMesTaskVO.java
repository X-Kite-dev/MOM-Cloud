package com.kitex.mom.oms.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 生产工单
 *
 * @author kitex
 * @since 2025-05-05
 */
@Data
@Schema(description = "生产工单")
public class OmsLifeMesTaskVO {

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
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planStartDate;

    @Schema(description = "计划结束时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndDate;

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

    @Schema(description = "成品待入库量")
    private BigDecimal unInStockNum;

    @Schema(description = "不合格品数量")
    private BigDecimal unPassNum;

    @Schema(description = "已下达数量")
    private BigDecimal alreadyNoticeNum;

    @Schema(description = "开始时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @Schema(description = "完成时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishTime;

    @Schema(description = "状态 新建、下达、已派工、生产中、完成")
    private String status;

    @Schema(description = "bomId")
    private String bomId;

    @Schema(description = "bom快照")
    private String bomSnapshot;

    @Schema(description = "bom版本")
    private String bomVersion;

    @Schema(description = "工艺路线id")
    private Long processRouteId;

    @Schema(description = "工艺路线快照")
    private String processRouteSnapshot;

    @Schema(description = "工艺路线版本")
    private String processRouteCode;

    @Schema(description = "来源类型")
    private String resourceType;

    @Schema(description = "来源id")
    private String resourceId;

    @Schema(description = "车间编码")
    private String workshopCode;

    @Schema(description = "车间名称")
    private String workshopName;

    @Schema(description = "包装编码")
    private String wrapCode;

    @Schema(description = "包装容量")
    private String wrapCapacity;

    @Schema(description = "批次号生成规则")
    private String batchRuleCode;

    @Schema(description = "流转卡绑定")
    private String transferCode;

    @Schema(description = "工厂编码")
    private String factoryCode;
}
