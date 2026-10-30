package com.kitex.mom.mes.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 过程组合只描述职责与实际单据绑定，不新增生产实绩。
 */
public final class MesProcessCompositionDTO {

    private MesProcessCompositionDTO() {
    }

    @Data
    public static class Definition {
        private List<Level> levels;
    }

    @Data
    public static class Level {
        private String key;
        private String label;
        /**
         * ORDER归属、TASK工单、OPERATION工艺、ASSIGNMENT资源派工。
         */
        private List<String> bindings;
    }

    @Data
    public static class Draft {
        private String id;
        private Long revisionNo;
        private String code;
        private String name;
        private Definition definition;
    }

    @Data
    public static class Template {
        private String id;
        private String code;
        private String name;
        private Integer versionNo;
        private Long revisionNo;
        private String status;
        private Definition definition;
    }

    @Data
    public static class Run {
        private String requestId;
        private String templateId;
        private Long expectedRevisionNo;
        private List<MesTaskArrangeDTO> dispatches;
    }

    @Data
    public static class Node {
        private String id;
        private String parentId;
        private String levelKey;
        private String label;
        private List<String> bindings;
        private String sourceCode;
        private String taskId;
        private String produceTaskId;
        private String arrangeId;
        private String routeNodeCode;
    }

    @Data
    public static class Progress {
        private String taskCode;
        private String materialCode;
        private BigDecimal plannedQuantity;
        private BigDecimal completedQuantity;
        private String status;
    }

    @Data
    public static class View {
        private String id;
        private String requestId;
        private String templateId;
        private Integer templateVersion;
        private String templateName;
        private Definition definition;
        private List<Node> nodes;
        private List<Progress> progress;
        private String quantityBasis;
    }

    @Data
    public static class Receipt {
        /**
         * ABSENT不证明请求从未到达；APPLIED为原运行，CANCELLED为持久作废凭证。
         */
        private String status;
        private View run;
    }
}
