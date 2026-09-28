package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 当前用户需要做的工单信息
 *
 * @author kitex
 * @since 2025-05-06
 */
@Data
@Schema(description = "当前用户需要做的工单信息")
public class MesTaskCurrentWorkVO {

    @Schema(description = "派工单ID")
    private Long arrangeId;

    @Schema(description = "派工单号")
    private String arrangeCode;

    @Schema(description = "生产工单号")
    private String taskCode;

    @Schema(description = "工序任务单号")
    private String produceTaskCode;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "产品规格")
    private String productSpec;

    @Schema(description = "工序编码")
    private String routeCode;

    @Schema(description = "工序名称")
    private String routeName;

    @Schema(description = "工序类型")
    private String routeType;

    @Schema(description = "冻结的生产执行与报工契约")
    private MesExecutionContractDTO executionContract;

    @Schema(description = "工位编码")
    private String worksiteCode;

    @Schema(description = "工位名称")
    private String worksiteName;

    @Schema(description = "报工触发类型：MANUAL/AUTO")
    private String worksiteType;

    @Schema(description = "下达数量")
    private BigDecimal noticeNum;

    @Schema(description = "完成数量")
    private BigDecimal completeNum;

    @Schema(description = "不合格品数量")
    private BigDecimal unPassNum;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "车间编码")
    private String workshopCode;

    @Schema(description = "车间名称")
    private String workshopName;

    @Schema(description = "班次编码")
    private String shiftCode;

    @Schema(description = "班次名称")
    private String shiftName;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "包装规则编码")
    private String wrapRuleCode;

    @Schema(description = "包装容量")
    private String wrapCapacity;

    @Schema(description = "是否已开始")
    private Boolean isStarted;

    @Schema(description = "优先级（数字越小优先级越高）")
    private Integer priority;
}
