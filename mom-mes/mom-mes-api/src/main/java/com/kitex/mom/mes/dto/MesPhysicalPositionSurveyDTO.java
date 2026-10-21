package com.kitex.mom.mes.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 真实母件位置采集协议，与旧转换命令和数学预检分开。
 * 母件对象数量固定一个；所有库存身份、计量和位置数量以文本交付。
 * 位置确认不代表质量放行、库存化、来源占用或转换许可。
 */
public final class MesPhysicalPositionSurveyDTO {
    private MesPhysicalPositionSurveyDTO() {
    }

    /**
     * 完整现场观察原正文，不接收客户端工程、质量或来源资格标记。
     */
    @Data
    public static class Body {
        /**
         * 独立协议版本MES_PHYSICAL_POSITION_SURVEY_V1。
         */
        private String version;
        /**
         * 观察租户，只与服务器实际租户比对。
         */
        private String expectedTenantId;
        /**
         * 观察工厂，只与factory-code选择的实际工厂比对。
         */
        private String expectedFactoryCode;
        /**
         * 观察本人ID，只与真实认证比对。
         */
        private String expectedActorUserId;
        /**
         * 明确MES_PHYSICAL_SOURCE_V2，不按SHA猜来源算法。
         */
        private String expectedSourceVersion;
        /**
         * 一个母件全部精确正量来源，不聚成假库存或取首报告。
         */
        private List<Source> sources;
        /**
         * 原工程完整位置集合，每位实际存在/缺位/未明确。
         */
        private List<Slot> positions;
        /**
         * 现场采集说明，保留实际原文。
         */
        private String reason;
    }

    /**
     * 精确实际库存来源及完整V2观察指纹。
     */
    @Data
    public static class Source {
        /**
         * 实际库存主键文本。
         */
        private String stockId;
        /**
         * MES_PHYSICAL_SOURCE_V2完整原事实SHA。
         */
        private String expectedFingerprint;
    }

    /**
     * 每位存在状态由现场明确，质量不能由客户端声明GOOD。
     */
    @Data
    public static class Slot {
        /**
         * 原发布工程完整布局中的位置编码。
         */
        private String positionCode;
        /**
         * STOCKED/UNSTOCKED/MISSING/UNKNOWN，不默认存在或合格。
         */
        private String presence;
        /**
         * 该位至全部真实库存的SN当量边；非STOCKED必须为空。
         */
        private List<Allocation> allocations;
    }

    /**
     * 保留每位的全部精确库存来源，数量使用工程outputUnit当量。
     */
    @Data
    public static class Allocation {
        /**
         * 必须属于原sources全集，不接受同批/FIFO替换。
         */
        private String stockId;
        /**
         * 正量精确当量；一个STOCKED位置全部分配合计1。
         */
        private String quantity;
    }

    /**
     * 明确异人确认/驳回或本人取消；原采集内容永久不改。
     */
    @Data
    public static class Decision {
        /**
         * 独立MES_PHYSICAL_POSITION_DECISION_V1协议。
         */
        private String version;
        /**
         * 仅核对实际租户。
         */
        private String expectedTenantId;
        /**
         * 仅核对实际工厂。
         */
        private String expectedFactoryCode;
        /**
         * 本次操作实际用户，不接管原采集人身份。
         */
        private String expectedActorUserId;
        /**
         * 原采集全文SHA。
         */
        private String surveyFingerprint;
        /**
         * 原服务器完整证据SHA。
         */
        private String evidenceFingerprint;
        /**
         * 只能基于明确OBSERVED原状态，不自动确认未知结果。
         */
        private String expectedStatus;
        /**
         * CONFIRM/REJECT/CANCEL，不能提交质量放行或执行状态。
         */
        private String action;
        /**
         * 本次现场确认、驳回或取消依据。
         */
        private String reason;
    }

    /**
     * 永久采集及全部决策回执，状态只证明观察工作流，不授予库存转换许可。
     */
    @Data
    public static class Receipt {
        /**
         * 永久原采集号，不因结果不明另号重投。
         */
        private String requestId;
        /**
         * 实际主账ID文本。
         */
        private String surveyId;
        /**
         * 实际租户文本。
         */
        private String tenantId;
        /**
         * 实际工厂。
         */
        private String factoryCode;
        /**
         * 原采集本人ID文本。
         */
        private String observerUserId;
        /**
         * 原采集本人账号。
         */
        private String observerCode;
        /**
         * OBSERVED/CONFIRMED/REJECTED/CANCELLED/ABSENT/UNAVAILABLE。
         */
        private String status;
        /**
         * 原始采集字符串，不按当前DTO重写。
         */
        private String surveySnapshot;
        /**
         * 采集自身版本域SHA。
         */
        private String surveyFingerprint;
        /**
         * 原服务器完整锁内证据字符串。
         */
        private String evidenceSnapshot;
        /**
         * 独立证据域SHA。
         */
        private String evidenceFingerprint;
        /**
         * 首次采集时间文本。
         */
        private String observedTime;
        /**
         * 末次明确决策时间，待确认时为空。
         */
        private String decidedTime;
        /**
         * 全部原始操作按实际修订序排列，不分页截断。
         */
        private List<Event> events;
        /**
         * 固定false，位置观察确认不是质量/库存/资格/APPLIED。
         */
        private Boolean executionAvailable;
        /**
         * 是否存在可核验的完整永久采集记录。
         */
        private Boolean persisted;
        /**
         * 当前工作流边界与待建设内容。
         */
        private String message;
    }

    /**
     * 不可改删的原采集/决策审计，ID和修订序均文本。
     */
    @Data
    public static class Event {
        /**
         * 永久事件ID。
         */
        private String eventId;
        /**
         * 永久操作原号。
         */
        private String operationRequestId;
        /**
         * 实际操作用户ID。
         */
        private String actorUserId;
        /**
         * 实际操作账号。
         */
        private String actorCode;
        /**
         * OBSERVE/CONFIRM/REJECT/CANCEL。
         */
        private String action;
        /**
         * 修订序，当前最多两次，不通过时间猜排序。
         */
        private String revision;
        /**
         * 原操作原字符串。
         */
        private String requestSnapshot;
        /**
         * 对应操作版本域SHA。
         */
        private String requestFingerprint;
        /**
         * 事件发生时间。
         */
        private String occurredTime;
        /**
         * 异人确认时服务器重新读取的完整来源包，其他动作为空。
         */
        private String verificationSnapshot;
        /**
         * 确认重新读取包的自身BUNDLE_V2域SHA，其他动作为空。
         */
        private String verificationFingerprint;
    }

    /**
     * 位置只读预检的完整服务器证据，不是永久登记或执行回执。
     */
    @Data
    public static class Preview {
        /**
         * 独立预检版本。
         */
        private String version;
        /**
         * 本次完整采集原字符串，不用当前DTO重写原字符。
         */
        private String surveySnapshot;
        /**
         * 独立版本域加原字符串的SHA。
         */
        private String surveyFingerprint;
        /**
         * 服务器完整证据原字符串。
         */
        private String evidenceSnapshot;
        /**
         * 独立证据版本域SHA。
         */
        private String evidenceFingerprint;
        /**
         * 真实租户。
         */
        private String tenantId;
        /**
         * 真实工厂。
         */
        private String factoryCode;
        /**
         * 实际本人ID文本。
         */
        private String actorUserId;
        /**
         * 实际账号。
         */
        private String actorCode;
        /**
         * 原来源的规范码；候选母件现场身份仍须登记及异人确认，不自动证明物理组装。
         */
        private String canonicalIdentifier;
        /**
         * 本次明确声明的候选母件对象数为文本1，不与库存native量混用或冒称已确认实物。
         */
        private String physicalObjectCount;
        /**
         * 完整实际来源观察包，内含原V1/V2事实与当前阻断。
         */
        private MesPhysicalTransformSourceBundleDTO sourceBundle;
        /**
         * 原派工冻结的正式工程，不查询当前模板补造。
         */
        private String standardId;
        /**
         * 原正式工程内容SHA。
         */
        private String standardFingerprint;
        /**
         * 原发布工程布局编码。
         */
        private String layoutCode;
        /**
         * 全部明确位置，不以分页截断。
         */
        private List<Slot> positions;
        /**
         * 每条库存的实际完整当量，不合并成首来源。
         */
        private Map<String, String> sourceEquivalents;
        /**
         * STOCKED位置数文本。
         */
        private String stockedPositionCount;
        /**
         * 明确有件但未库存化位置数文本，不产生虚假stock。
         */
        private String unstockedPositionCount;
        /**
         * 明确缺位数文本，零实物/零库存/零分配。
         */
        private String missingPositionCount;
        /**
         * 尚不能确认存在状态的位置数文本。
         */
        private String unknownPositionCount;
        /**
         * 原来源和现场事实仍有的具体执行资格缺口。
         */
        private List<String> limitations;
        /**
         * 固定false；预检尚未保存永久位置记录。
         */
        private Boolean persisted;
        /**
         * 固定false；完整资格、实际库存/余额/谱系及三端仍须接入。
         */
        private Boolean executionAvailable;
        /**
         * 当前位置预检与永久执行的边界。
         */
        private String message;
    }
}
