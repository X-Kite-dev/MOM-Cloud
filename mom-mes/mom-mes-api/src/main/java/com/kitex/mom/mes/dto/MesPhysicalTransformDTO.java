package com.kitex.mom.mes.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 实物转换的来源、投入产出分配及数学预检协议。
 * 所有身份和计量均为文本；预检不登记原号、不写库存，也不是生产放行凭证。
 */
public final class MesPhysicalTransformDTO {

    private MesPhysicalTransformDTO() {
    }

    /**
     * 尚未发布/冻结的计算政策草案；执行阶段必须另读真实冻结政策。
     */
    @Data
    public static class Policy {
        /**
         * 严格政策版本：MES_PHYSICAL_TRANSFORM_POLICY_V1。
         */
        private String version;
        /**
         * 业务动作：SPLIT、MERGE、REBATCH、DEPANEL。
         */
        private String operationType;
        /**
         * 投入对象：BATCH、SN、TRAVELER、PANEL。
         */
        private String inputObjectType;
        /**
         * 实物产出对象：BATCH、SN、TRAVELER，不使用载具替代实物。
         */
        private String outputObjectType;
        /**
         * 与原库存一致的投入单位。
         */
        private String inputUnit;
        /**
         * 全部分配及产出统一使用的当量单位。
         */
        private String outputUnit;
        /**
         * 一投入单位对应的产出当量；文本精确小数，不构成工程换算许可。
         */
        private String equivalenceFactor;
        /**
         * 是否允许投入保留真实余量；单件SN始终不可部分投入。
         */
        private Boolean partialInputAllowed;
        /**
         * 面板完整位置集合；非拆板动作必须为空。
         */
        private List<String> positionCodes;
    }

    /**
     * 纯计算预检的完整原正文，不是永久执行命令。
     */
    @Data
    public static class Plan {
        /**
         * 客户端观察的租户ID，只与实际上下文比对。
         */
        private String expectedTenantId;
        /**
         * 客户端观察的工厂，只比对、不设置服务端工厂。
         */
        private String expectedFactoryCode;
        /**
         * 客户端观察的本人ID，只与实际认证比对。
         */
        private String expectedActorUserId;
        /**
         * 严格JSON政策草案文本；不能用于直接放行库存事务。
         */
        private String policyDraft;
        /**
         * 精确来源行、观察指纹和本次投入量。
         */
        private List<Input> inputs;
        /**
         * 完整输出及位置/合格、报废、缺位声明。
         */
        private List<Output> outputs;
        /**
         * 每一真实投入到每一输出的当量分配，不允许重复边。
         */
        private List<Allocation> allocations;
        /**
         * 人工填写的业务原因，保留原文而不静默清洗。
         */
        private String reason;
    }

    /**
     * 精确投入行；不是按物料/批次选择FIFO候选。
     */
    @Data
    public static class Input {
        /**
         * 当前实物库存主键文本。
         */
        private String stockId;
        /**
         * 来源接口交付的完整事实SHA-256。
         */
        private String expectedFingerprint;
        /**
         * 本次投入量，使用真实原库存单位。
         */
        private String quantity;
    }

    /**
     * 实物输出或面板缺位；缺位不产生标识或库存。
     */
    @Data
    public static class Output {
        /**
         * 本正文内唯一输出键，供精确分配引用。
         */
        private String outputKey;
        /**
         * 扫描/确认的新规范标识；MISSING必须为空，不生成虚假SN。
         */
        private String identifier;
        /**
         * 与政策一致的输出对象类型。
         */
        private String objectType;
        /**
         * 产出当量文本；SN为1，缺位为0。
         */
        private String quantity;
        /**
         * GOOD、SCRAP、MISSING；报废不能作为合格产量。
         */
        private String disposition;
        /**
         * 板位编码；非拆板动作必须为空。
         */
        private String positionCode;
    }

    /**
     * 同一投入/输出组合只能有一条正量当量边。
     */
    @Data
    public static class Allocation {
        /**
         * 真实投入库存主键，与inputs精确一致。
         */
        private String stockId;
        /**
         * 正文内真实非缺位输出键。
         */
        private String outputKey;
        /**
         * 分配当量文本，使用统一outputUnit。
         */
        private String quantity;
    }

    /**
     * 当前范围内真实来源的完整标量事实与已知阻断原因。
     */
    @Data
    public static class SourceFact {
        /**
         * 当前精确库存主键。
         */
        private String stockId;
        /**
         * 冻结原报工主键；空或失效时不得猜来源。
         */
        private String sourceReportId;
        /**
         * 原报工真实工单编码。
         */
        private String taskCode;
        /**
         * 原库存的实物码；空值保持原历史，不补造SN。
         */
        private String identifier;
        /**
         * 实际物料编码。
         */
        private String materialCode;
        /**
         * 实际物料计量单位。
         */
        private String unit;
        /**
         * 当前库存量文本。
         */
        private String quantity;
        /**
         * 原报工冻结的实际模式；不拿当前模板补造历史。
         */
        private String reportMode;
        /**
         * 当前同实物标识的完整正量库存主键；不能只挑其中一行伪装整件。
         */
        private List<String> peerStockIds;
        /**
         * 完整库存及关联原报工/派工标量事实JSON。
         */
        private String factSnapshot;
        /**
         * MES_PHYSICAL_SOURCE_V1|factSnapshot的SHA-256。
         */
        private String fingerprint;
        /**
         * 当前已知来源检查通过，不等于完整实物转换执行资格。
         */
        private Boolean sourceChecksPassed;
        /**
         * 独立质量、原来源、在途、未决等具体已知阻断原因。
         */
        private List<String> blockingReasons;
    }

    /**
     * 同一只读事务观察到的完整来源集合与实际owner。
     */
    @Data
    public static class SourceView {
        /**
         * 协议版本：MES_PHYSICAL_SOURCE_VIEW_V1。
         */
        private String version;
        /**
         * 实际租户ID文本。
         */
        private String tenantId;
        /**
         * 实际工厂。
         */
        private String factoryCode;
        /**
         * 实际认证用户ID文本。
         */
        private String actorUserId;
        /**
         * 实际认证账号。
         */
        private String actorCode;
        /**
         * 按精确库存ID排序的完整来源，不截断。
         */
        private List<SourceFact> sources;
    }

    /**
     * 统一当量校验结果；无产量或库存写。
     */
    @Data
    public static class Calculation {
        /**
         * 动作代码。
         */
        private String operationType;
        /**
         * 投入单位。
         */
        private String inputUnit;
        /**
         * 统一当量单位。
         */
        private String outputUnit;
        /**
         * 原始投入量合计，保持原单位。
         */
        private String inputQuantity;
        /**
         * 全部投入乘精确系数后的当量合计。
         */
        private String inputEquivalent;
        /**
         * GOOD输出当量合计。
         */
        private String goodQuantity;
        /**
         * SCRAP输出当量合计。
         */
        private String scrapQuantity;
        /**
         * 明确零量缺位数，文本交付。
         */
        private String missingPositionCount;
        /**
         * 每条真实源行在本次投入后的计算余量。
         */
        private Map<String, String> sourceRemainders;
        /**
         * 每条真实源行的投入当量。
         */
        private Map<String, String> sourceEquivalents;
        /**
         * 本次未选位置全集；实际执行必须由永久位置余额账验证。
         */
        private List<String> remainingPositions;
    }

    /**
     * 数学预检完整证据；明确无执行放行能力。
     */
    @Data
    public static class Preview {
        /**
         * MES_PHYSICAL_PREVIEW_V1。
         */
        private String version;
        /**
         * 本次实际范围和来源事实。
         */
        private SourceView sourceView;
        /**
         * 精确计量/分配核对结果。
         */
        private Calculation calculation;
        /**
         * 含实际owner、完整规范正文和来源事实的规范JSON。
         */
        private String previewSnapshot;
        /**
         * MES_PHYSICAL_PREVIEW_V1|previewSnapshot的SHA-256。
         */
        private String fingerprint;
        /**
         * 当前第一段固定false，后续不得把预检SHA作为写许可。
         */
        private Boolean executionAvailable;
        /**
         * 数学校验与生产执行/冻结资格的明确边界。
         */
        private String message;
    }
}
