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

    // ---------- 批次流程图 ----------

    private List<FlowDataDTO> flowData;
    @Schema(description = "物流与分销")
    private List<LogisticsDTO> logistics;

    @Data
    public static class FlowDataDTO {

        @Schema(description = "工序编码")
        private String routeCode;

        @Schema(description = "工序名称")
        private String routeName;

        @Schema(description = "子节点")
        private List<FlowDataDTO> children;

    }
}
