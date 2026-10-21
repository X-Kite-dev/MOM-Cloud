package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 供实施人员选择后再补充实际作用域的行业执行模板起点。
 *
 * <p>目录说明当前可配置的核心能力及人工核对要求，不代表已安装行业能力包；
 * 专用能力缺口不能作为执行模板开关保存，也不能进入派工冻结契约。</p>
 */
@Data
@AllArgsConstructor
@Schema(description = "行业生产执行模板建议")
public class MesIndustryExecutionPresetDTO {

    /**
     * 行业或共享工艺族的稳定编码；不是报工模式或执行引擎分支。
     */
    @Schema(description = "行业或共享工艺族编码，仅用于配置目录标识")
    private String industryCode;

    /**
     * 行业或共享工艺族的中文名称。
     */
    @Schema(description = "行业或共享工艺族名称")
    private String industryName;

    /**
     * 当前统一执行内核可提供的配置起点说明。
     */
    @Schema(description = "当前可用配置起点说明，不代表完整行业能力已实现")
    private String description;

    /**
     * 只包含当前执行模板支持字段的场景草稿建议。
     */
    @Schema(description = "可应用到执行模板草稿的场景建议，应用后仍需填写真实作用域并发布")
    private List<Scenario> scenarios;

    /**
     * 可组合采用本目录建议的生产组织与工艺形态。
     */
    @Schema(description = "生产形态参考，同一工厂或产品可以组合多种形态，不形成新的报工模式")
    private List<String> productionForms;

    /**
     * 保存或发布草稿前需要实施人员核对的现有配置及业务依据。
     */
    @Schema(description = "配置核对项，仅核对现有模板、工艺、资源和业务依据，不提供专用缺口开关")
    private List<String> configurationChecklist;

    /**
     * 需要真实岗位和实物完成的现场人工验证项。
     */
    @Schema(description = "人工验证项，配置保存或代码编译通过不代表这些业务链路已验收")
    private List<String> verificationChecklist;

    /**
     * 当前建议及统一执行内核尚未闭环的行业专用能力。
     */
    @Schema(description = "未覆盖的专用能力，不能通过应用本建议获得或视为派工冻结策略")
    private List<String> uncoveredCapabilities;

    /**
     * 兼容原行业目录的四参数构造调用；新增说明默认空目录。
     */
    public MesIndustryExecutionPresetDTO(String industryCode, String industryName, String description,
                                         List<Scenario> scenarios) {
        this(industryCode, industryName, description, scenarios,
                List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 与当前生产执行模板字段一致的可编辑场景草稿。
     */
    @Data
    @AllArgsConstructor
    @Schema(description = "行业生产执行场景建议")
    public static class Scenario {

        /**
         * 行业内的稳定场景编码；原场景编码保持兼容。
         */
        @Schema(description = "行业内场景编码")
        private String scenarioCode;

        /**
         * 场景的中文名称。
         */
        @Schema(description = "场景名称")
        private String scenarioName;

        /**
         * 当前核心字段能够表达的执行场景及边界说明。
         */
        @Schema(description = "场景说明，仅描述当前可配置的执行能力")
        private String description;

        /**
         * 五种稳定的生产对象报工模式之一。
         */
        @Schema(description = "报工模式：QUANTITY、ONE_CODE、TRANSFER_CARD、PACKAGE_ONE_CODE或PACKAGE_QUANTITY")
        private String reportMode;

        /**
         * 后端可信入口允许的人工、设备事件或集成触发来源。
         */
        @Schema(description = "允许触发来源：MANUAL、AUTO_EVENT、API；由后端可信入口核验")
        private List<String> allowedTriggers;

        /**
         * 是否允许在派工数量范围内分次报工。
         */
        @Schema(description = "是否允许分批报工，不代表支持实物批次拆分或合并")
        private Boolean partialReportAllowed;

        /**
         * 是否允许按模板比例超过派工数量。
         */
        @Schema(description = "是否允许超报，不能绕过前序、物料、质量和资源门")
        private Boolean overReportAllowed;

        /**
         * 超报比例；零表示不增加允许报工数量。
         */
        @Schema(description = "允许超报比例，例如0.05表示5%；本目录建议默认不超报")
        private BigDecimal overReportRate;

        /**
         * 是否要求报工绑定通过运行门的设备。
         */
        @Schema(description = "是否要求设备绑定及运行资格核验")
        private Boolean deviceRequired;

        /**
         * 是否要求已有计划工装及其现场执行核验。
         */
        @Schema(description = "是否要求工装核验，须有实际派工冻结的计划工装依据")
        private Boolean toolingRequired;

        /**
         * 是否开放经过统一规则核验的移动端入口。
         */
        @Schema(description = "是否允许移动端报工")
        private Boolean mobileAllowed;

        /**
         * 是否开放经过统一规则核验的工站端入口。
         */
        @Schema(description = "是否允许PAD或固定工站端报工")
        private Boolean stationAllowed;

        /**
         * 已发布静态标识映射的解析方式，不包含动态换码绑定。
         */
        @Schema(description = "静态标识解析方式：CANONICAL_ONLY、ALLOW_MAPPING或MAPPING_REQUIRED，不支持节点式动态换码绑定")
        private String identifierResolveMode;

        /**
         * 允许命中已发布静态映射的标识类型。
         */
        @Schema(description = "允许的静态映射标识类型：TEMPORARY、ALTERNATE、EXTERNAL、CUSTOMER、SUPPLIER、LEGACY")
        private List<String> allowedIdentifierTypes;
    }
}
