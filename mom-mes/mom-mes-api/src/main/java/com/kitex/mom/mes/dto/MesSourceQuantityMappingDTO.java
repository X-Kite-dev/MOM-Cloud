package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * 某次MES来源数量请求实际形成的逐PLAN计划分录；供上游事实源做精确回执对账。
 */
@Data
@Accessors(chain = true)
@Schema(description = "MES来源数量请求的逐PLAN计划权威分录")
public class MesSourceQuantityMappingDTO {
    private String requestId;
    private String requestFingerprint;
    private String eventKind;
    private Long mesTaskId;
    private String mesTaskCode;
    private String apsBatchNo;
    private String factoryCode;
    /**
     * APS_SOURCE_EVENT、LEGACY_PLAN_NO或NO_QUANTITY_EVENT。
     */
    private String mappingStatus;
    private Boolean mappingComplete;
    private String mappingMessage;
    private String mappingFingerprint;
    private BigDecimal eventQuantity;
    private List<Allocation> allocations;

    @Data
    @Accessors(chain = true)
    public static class Allocation {
        private Long issueId;
        private String issueNo;
        /**
         * 原APS批次分配给该计划的生产数量。
         */
        private BigDecimal sourceAllocatedQuantity;
        /**
         * 本次请求实际归集给该计划的数量增量。
         */
        private BigDecimal eventQuantity;
        /**
         * 本次请求处理后该类数量的累计值。
         */
        private BigDecimal quantityAfter;
    }
}
