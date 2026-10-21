package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 工序进程
 *
 * @author kitex
 * @since 2025-04-14
 */
@Data
public class RouteProcessDTO {

    @Schema(description = "工序任务编码")
    private String produceTaskCode;

    @Schema(description = "工单编码")
    private String taskCode;

    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "物料规格")
    private String matSpec;

    @Schema(description = "工作中心编码")
    private String worksiteCodes;

    @Schema(description = "工作中心")
    private String worksiteNames;

    @Schema(description = "完成数量")
    private BigDecimal finishNum;

    @Schema(description = "工序编码")
    private String processCode;

    @Schema(description = "工序名称")
    private String processName;

    @Schema(description = "实际任务冻结的路线节点，历史允许为空")
    private String sourceRouteNodeCode;

    @Schema(description = "完整执行图协议；不能仅由节点编码推断")
    private String routeExecutionSchemaVersion;

    @Schema(description = "冻结前序节点CSV")
    private String predecessorNodeCodes;

    @Schema(description = "冻结后序节点CSV")
    private String successorNodeCodes;

    @Schema(description = "未释放的前序节点CSV，旧合同为空")
    private String blockingPredecessorNodeCodes;

    @Schema(description = "工序状态")
    private String status;

    @Schema(description = "直接前置工序编码，多个逗号分隔")
    private String predecessorCodes;

    @Schema(description = "直接后置工序编码，多个逗号分隔")
    private String successorCodes;

    @Schema(description = "全部直接前置工序是否已完成")
    private Boolean readyToStart;

    @Schema(description = "尚未完成或缺失的前置工序")
    private String blockingPredecessorCodes;

    private Boolean overlapEnabled;
    private BigDecimal transferBatchQuantity;

    @Schema(description = "百分比")
    private BigDecimal percent;

}
