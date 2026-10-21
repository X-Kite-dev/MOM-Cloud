package com.kitex.mom.mes.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MesReworkMaterialCommand {
    private Long taskId;
    private Long planId;
    private String operation;
    private String requestId;
    private String reason;
    private Boolean noMaterialConfirmed;
    /** 首轮返工执行方案必须明确时间口径；追加用料不能借机改变。 */
    private Timing timing;
    /** 首轮方案须明确是否引用原生产工单的动态外码实例；追加方案沿用当前批准范围。 */
    private IdentifierScope identifierScope;
    /** 首轮方案必须明确继承原节点或使用精确已批准返工覆盖版。 */
    private EngineeringOverride engineeringOverride;
    private List<Item> items;

    /**
     * 返工标识范围不是新的换码配置。SOURCE_BINDINGS只接受服务端候选中的精确实例ID，
     * NONE表示原实际工序不在动态区段，不能用空值替代明确判断。
     */
    @Data
    public static class IdentifierScope {
        private String mode;
        private List<String> sourceBindingIds;
    }

    @Data
    public static class Timing {
        private String mode;
        private String scope;
        private String sourceEvent;
        private String minSeconds;
        private String maxSeconds;
        /** 超过最晚允许后，经独立审批仍可接受的最大附加秒数；空表示禁止例外。 */
        private String lateExceptionSeconds;
    }

    /** SOP/参数覆盖是工程批准版本选择，不接受客户端提交任意正文。 */
    @Data
    public static class EngineeringOverride {
        /** INHERIT_SOURCE或APPROVED_OVERRIDE。 */
        private String mode;
        /** APPROVED_OVERRIDE必填的MDM工程版本ID。 */
        private String releaseId;
        /** APPROVED_OVERRIDE必填的原文SHA256。 */
        private String contentHash;
    }

    @Data
    public static class Item {
        private String materialCode;
        private BigDecimal quantityPerUnit;
    }
}
