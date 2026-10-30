package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * 产品需求滚动重排的冻结证据。
 *
 * <p>供 MPS 等来源权威流程复用，覆盖旧 MRP 下层供应、根生产计划、MES 撤回资格以及
 * APS 独占占用。该对象会整体序列化保存，执行和失败续跑不得重新选择另一批下游事实。</p>
 */
public final class PlnDemandReplanEvidenceDTO {

    private PlnDemandReplanEvidenceDTO() {
    }

    /**
     * 一条需求的完整下游证据。
     */
    @Data
    @Accessors(chain = true)
    public static class Evidence {
        /**
         * DIRECT 或 WITHDRAW_REPLAN。
         */
        private String mode;
        /**
         * 产品需求主键。
         */
        private Long demandId;
        /**
         * 产品需求编号。
         */
        private String demandNo;
        /**
         * 工厂编码。
         */
        private String factoryCode;
        /**
         * 被替换的活动 MPS 行；兼容旧直达 MRP 时为空。
         */
        private Long previousMpsLineId;
        /**
         * 被替换的全部活动MPS桶行；旧证据为空时继续使用previousMpsLineId。
         */
        private List<Long> previousMpsLineIds;
        /**
         * PLAN/MRP/APS 本地事实指纹。
         */
        private String localFingerprint;
        /**
         * 下层自制、采购和外协供应指纹。
         */
        private String supplyFingerprint;
        /**
         * 本地、供应和 MES 的组合指纹。
         */
        private String evidenceFingerprint;
        /**
         * 冻结的 MRP 主键。
         */
        private List<Long> mrpIds;
        /**
         * 冻结的下层供应撤回证据。
         */
        private List<PlanMrpSupplyWithdrawalDTO.Evidence> supplyEvidence;
        /**
         * 冻结的根生产计划及 MES 撤回资格。
         */
        private List<IssueEvidence> issues;
    }

    /**
     * 一个根生产计划的撤回证据。
     */
    @Data
    @Accessors(chain = true)
    public static class IssueEvidence {
        /**
         * 生产计划主键。
         */
        private Long issueId;
        /**
         * 生产计划编号。
         */
        private String issueNo;
        /**
         * 冻结时计划状态：0 未下达、1 已下达。
         */
        private String issueStatus;
        /**
         * 计划数量。
         */
        private BigDecimal quantity;
        /**
         * MES 撤回资格指纹；未下达计划使用固定占位指纹。
         */
        private String mesEvidenceFingerprint;
    }

    /**
     * 一条需求完成撤回后的结果摘要。
     */
    @Data
    @Accessors(chain = true)
    public static class Receipt {
        /**
         * 产品需求主键。
         */
        private Long demandId;
        /**
         * 撤回的下层供应数量。
         */
        private Integer withdrawnSupplyCount;
        /**
         * 关闭的根生产计划数量。
         */
        private Integer closedIssueCount;
        /**
         * 处理说明。
         */
        private String message;
    }
}
