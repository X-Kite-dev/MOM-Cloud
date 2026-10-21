package com.kitex.mom.mdm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.mom.mdm.entity.MdmBomEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.vo.MdmProcessRouteProcedureVO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 工程联合发布固定合同。工程批准与生产消费分别表达；客户端不能提交任意工程快照。
 * 长整型标识以文本出网，内容指纹对应服务端实际持久化的原始 UTF-8 文本。
 */
public final class MdmRouteEngineeringDTO {

    public static final String SCHEMA_VERSION = "MOM_ROUTE_ENGINEERING_V1";
    /** 返工覆盖包只包含一个稳定节点的正式SOP/参数，不是另一套生产路线。 */
    public static final String REWORK_OVERRIDE_SCHEMA_VERSION = "MOM_REWORK_ENGINEERING_OVERRIDE_V1";
    public static final String PURPOSE_PRODUCTION = "PRODUCTION";
    public static final String PURPOSE_REWORK_OVERRIDE = "REWORK_OVERRIDE";
    public static final String EXECUTION_NOTICE = "工程版本须完成发布并由PLAN绑定后才能进入APS/MES；生产侧按版本ID和原文指纹精确取包，旧默认路线入口不承载重复节点";

    private MdmRouteEngineeringDTO() {
    }

    /** 工程工作台上下文，仅返回当前真实组织的路线及候选 BOM。 */
    @Data
    @Accessors(chain = true)
    public static class Context {
        /** 路线主数据。 */
        private MdmProcessRouteEntity route;
        /** 全部稳定节点与权威前序关系。 */
        private MdmProcessRouteProcedureVO graph;
        /** 同产品当前正式、启用、日期有效的 BOM，仍由预检复核。 */
        private List<MdmBomEntity> boms = new ArrayList<>();
        /** 工程发布不能替代现场消费接入。 */
        private String executionNotice = EXECUTION_NOTICE;
    }

    /** 某节点某类工程依据的显式适用性，不从空数据推断“不适用”。 */
    @Data
    @Accessors(chain = true)
    public static class Applicability {
        /** 真实稳定节点编码 RN-id。 */
        private String routeNodeCode;
        /** SOP/PARAMETER/MATERIAL/TOOLING/MOLD/SETTING。 */
        private String category;
        /** REQUIRED/NOT_APPLICABLE。 */
        private String mode;
        /** 不适用的实际依据，非空且最多1000字符。 */
        private String reason;
    }

    /** 预检只能引用服务器上的正式来源及显式声明。 */
    @Data
    @Accessors(chain = true)
    public static class PreviewQuery {
        /** PRODUCTION或REWORK_OVERRIDE；历史空值按PRODUCTION解释。 */
        private String releasePurpose;
        /** 当前授权工厂的路线ID。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long routeId;
        /** 明确选择的正式 BOM ID，不能按默认版猜测。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long bomId;
        /** 页面读到的图版本；来源已改变时刷新后重采。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long expectedGraphRevision;
        /** 每节点六类完整声明。 */
        private List<Applicability> declarations = new ArrayList<>();
        /** 返工覆盖必须引用的原生产工程版本。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long baseReleaseId;
        /** 返工覆盖唯一目标稳定节点。 */
        private String targetRouteNodeCode;
        /** 返工覆盖类别，只允许SOP、PARAMETER且至少一项。 */
        private List<String> overrideCategories = new ArrayList<>();
    }

    /** 完整采集结果与阻断项，不把解析成功等同于工程齐套。 */
    @Data
    @Accessors(chain = true)
    public static class Preview {
        /** 服务器组装的不可变候选原文。 */
        private String bundleSnapshot;
        /** 原文 SHA256，必须在提交时原样回传。 */
        private String contentHash;
        /** 采集锁内的真实图版本。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long graphRevision;
        /** 适用声明、全部来源和组织核验均满足才为 true。 */
        private boolean ready;
        /** 需工程人员处理的完整阻断清单。 */
        private List<String> gaps = new ArrayList<>();
        /** 全部节点来源齐套时表示该候选包具备生产取包条件；仍须完成异岗发布和PLAN绑定。 */
        private boolean executionReady;
        /** 当前真实交付边界。 */
        private String executionNotice = EXECUTION_NOTICE;
        /** 本候选的明确发布用途。 */
        private String releasePurpose = PURPOSE_PRODUCTION;
    }

    /** 永久命令；重试必须携带同一原号、原载荷及原实名用户。 */
    @Data
    @Accessors(chain = true)
    public static class Command {
        /** 16至64位字母、数字、连字符或下划线。 */
        private String requestId;
        /** SUBMIT/APPROVE/REJECT/RETIRE/WITHDRAW。 */
        private String action;
        /** 非 SUBMIT 命令指向的精确版本。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long releaseId;
        /** 页面读取的生命周期修订指纹。 */
        private String expectedHash;
        /** SUBMIT 指向的实际路线。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long routeId;
        /** SUBMIT 选择的精确正式 BOM。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long bomId;
        /** SUBMIT 预检时的图版本。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long expectedGraphRevision;
        /** SUBMIT 预检原文 SHA256。 */
        private String previewHash;
        /** SUBMIT 的节点适用声明。 */
        private List<Applicability> declarations = new ArrayList<>();
        /** PRODUCTION或REWORK_OVERRIDE。 */
        private String releasePurpose;
        /** 返工覆盖的原生产工程版本。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long baseReleaseId;
        /** 返工覆盖唯一目标稳定节点。 */
        private String targetRouteNodeCode;
        /** 返工覆盖类别，只允许SOP、PARAMETER。 */
        private List<String> overrideCategories = new ArrayList<>();
        /** 申请、审批、退回、退役或安全撤回依据。 */
        private String reason;
    }

    /** 同一永久请求固定回执，不随当前版本状态变化。 */
    @Data
    @Accessors(chain = true)
    public static class Receipt {
        /** SUCCESS为工程动作完成，SEALED为该原命令未执行且已永久封存，今后不能重投。 */
        private String outcome = "SUCCESS";
        /** 原请求号。 */
        private String requestId;
        /** 实际版本ID，文本防止前端精度损失。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long releaseId;
        /** 路线内递增版本号。 */
        private Integer versionNo;
        /** 该命令完成时的状态。 */
        private String status;
        /** 原封存正文指纹。 */
        private String contentHash;
        /** 该命令完成时的生命周期修订指纹。 */
        private String revisionHash;
        /** 结果说明。 */
        private String message;
        /** SEALED回执绑定下方原命令的UTF-8字节SHA256。 */
        private String payloadHash;
        /** SEALED永久封存的完整命令；客户端核原号/语义/指纹后才能归档。 */
        private String commandSnapshot;
    }

    /**
     * 生产侧精确取包请求。调用方必须同时持有版本ID、原文指纹和产品编码，
     * 禁止只按“当前默认路线”或“最新工程版本”猜测生产配置。
     */
    @Data
    @Accessors(chain = true)
    public static class ProductionQuery {
        /** 已发布工程版本ID。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long releaseId;
        /** 已发布版本冻结原文的SHA256。 */
        private String contentHash;
        /** 本次计划或工单的产品编码。 */
        private String productCode;
    }

    /** PLAN/APS选择工程版本时使用的轻量候选，不包含大体积冻结正文。 */
    @Data
    @Accessors(chain = true)
    public static class ProductionCandidate {
        /** 已发布工程版本ID。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long releaseId;
        /** 路线内工程版本号。 */
        private Integer versionNo;
        /** 冻结原文SHA256，与releaseId共同组成生产版本对。 */
        private String contentHash;
        /** 精确路线ID。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long routeId;
        /** 精确路线编码。 */
        private String routeCode;
        /** 产品编码。 */
        private String productCode;
        /** 精确BOM版本ID。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long bomId;
        /** BOM业务版本。 */
        private String bomVersion;
        /** 冻结路线图版本。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long graphRevision;
    }

    /** MES返工方案可选择的已批准单节点覆盖版，不返回大体积冻结正文。 */
    @Data
    @Accessors(chain = true)
    public static class ReworkOverrideCandidate {
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long releaseId;
        private Integer versionNo;
        private String contentHash;
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long baseReleaseId;
        private String baseContentHash;
        private String routeCode;
        private String productCode;
        private String targetRouteNodeCode;
        private List<String> overrideCategories = new ArrayList<>();
        private String reviewedBy;
        private String reviewedTime;
    }

    /** MES按原生产版本、稳定节点和覆盖版本对精确取包。 */
    @Data
    @Accessors(chain = true)
    public static class ReworkOverrideQuery {
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long releaseId;
        private String contentHash;
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private Long baseReleaseId;
        private String baseContentHash;
        private String targetRouteNodeCode;
        private String productCode;
    }

    /** 经MDM重采核验的单节点返工覆盖包。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @Accessors(chain = true)
    public static class ReworkOverridePackage extends ReworkOverrideCandidate {
        private String schemaVersion;
        private String tenantId;
        private String factoryCode;
        /** 原封存覆盖包，包含下列两个逐字节子快照。 */
        private String bundleSnapshot;
        /** 仅目标节点的ROUTE_NODE_V2参数目录；未覆盖参数时为空。 */
        private String parameterCatalogSnapshot;
        /** 仅目标节点的FORMAL_SOP_MANIFEST_V1清单；未覆盖SOP时为空。 */
        private String sopManifestSnapshot;
    }

    /**
     * 服务端核验后的生产工程包。bundleSnapshot始终取自MDM原封存记录，
     * PLAN/MES不得接收客户端替换后的路线、BOM、参数或SOP正文。
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @Accessors(chain = true)
    public static class ProductionPackage extends ProductionCandidate {
        /** 固定工程包协议版本。 */
        private String schemaVersion;
        /** 当前实际租户，文本出网。 */
        private String tenantId;
        /** 当前实际工厂。 */
        private String factoryCode;
        /** 经当前来源重采精确匹配的原冻结UTF-8正文。 */
        private String bundleSnapshot;
    }
}
