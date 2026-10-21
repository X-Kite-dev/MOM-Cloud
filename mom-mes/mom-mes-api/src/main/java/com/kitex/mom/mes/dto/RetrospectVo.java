package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 追溯
 *
 * @author kitex
 * @since 2025-04-24
 */
@Data
public class RetrospectVo {
    @Schema(description = "证据模式；旧汇总不代表完整实际消耗谱系")
    private String evidenceMode;
    @Schema(description = "证据缺口及使用边界")
    private List<String> warnings;

    @Schema(description = "原材料信息")
    private List<MatRetrospectDTO> mat;

    @Schema(description = "生产数据")
    private List<MesRetrospectDTO> mes;

    @Schema(description = "质量检测")
    private List<QmsRetrospectDTO> qms;

    @Schema(description = "成品信息")
    private List<ProductRetrospectDTO> product;

    @Schema(description = "原实际工序任务图；不代表被查询单件已执行的路径")
    private List<FlowDataDTO> flowData;
    @Schema(description = "物流与分销")
    private List<LogisticsDTO> logistics;

    @Data
    public static class FlowDataDTO {

        @Schema(description = "实际工序任务主键，文本表示避免前端整数精度损失")
        private String produceTaskId;

        @Schema(description = "实际工序任务编码，不同执行节点不合并")
        private String produceTaskCode;

        @Schema(description = "原生产工单编码，依赖边不得跨工单")
        private String taskCode;

        @Schema(description = "原冻结工程路线编码，不从当前主数据回填")
        private String processRouteCode;

        @Schema(description = "原冻结稳定节点编码；历史协议可能为空")
        private String sourceRouteNodeCode;

        @Schema(description = "原执行图协议，NODE_EXECUTION_V1或历史NULL")
        private String routeExecutionSchemaVersion;

        @Schema(description = "完整NODE图核验后的实际前序任务编码；旧图或缺口不推造边")
        private List<String> predecessorTaskCodes;

        @Schema(description = "图证据状态：NODE已核节点图、LEGACY历史列表、INVALID图证据缺口")
        private String graphState;

        @Schema(description = "历史图展示边界或原冻结图核验失败原因")
        private String graphIssue;

        @Schema(description = "工序编码")
        private String routeCode;

        @Schema(description = "工序名称")
        private String routeName;

        @Schema(description = "旧树形字段，兼容保留空数组；真实依赖使用predecessorTaskCodes")
        private List<FlowDataDTO> children;

    }
}
