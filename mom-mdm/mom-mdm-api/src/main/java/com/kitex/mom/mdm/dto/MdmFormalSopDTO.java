package com.kitex.mom.mdm.dto;

import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 正式SOP固定领域合同。所有标识均以字符串出网，来源类型固定，不接收任意URL。
 * contentHash只标识版本内容；revisionHash另外包含生命周期变化，供编辑并发校验。
 */
public final class MdmFormalSopDTO {
    private MdmFormalSopDTO() { }

    /** 生命周期命令；不可省略永久请求号，重试必须携带原载荷。 */
    @Data
    @Accessors(chain = true)
    public static class Command {
        /** 永久请求号，16至64位字母、数字、连字符或下划线。 */
        private String requestId;
        /** CREATE/REVISE/UPDATE/SUBMIT/APPROVE/REJECT/RETIRE/WITHDRAW。 */
        private String action;
        /** 除CREATE之外必须指定的精确版本。 */
        private String versionId;
        /** 页面读取的原修订指纹，防止覆盖他人改动。 */
        private String expectedHash;
        /** 新版本族编号，仅CREATE使用。 */
        private String sopNo;
        /** 指导书名称。 */
        private String name;
        /** 原件上传回执给出的ID。 */
        private String originalId;
        /** 对原文的摘要说明，不能替代原件。 */
        private String description;
        /** 适用工件、工序及禁用范围。 */
        private String applicability;
        /** 审核、退回、退役、安全撤回的依据。 */
        private String reason;
        /** 从工序入口发起时的真实节点行ID；仅新建/修订使用，不代表已生效绑定。 */
        private String sourceNodeId;
        /** 历史附件转入的行ID；服务端核对所属节点，不接受任意文件URL。 */
        private String legacyAttachmentId;
        /** 页面读取的历史附件内容指纹，防止附件被替换后仍按原文件转入。 */
        private String legacyExpectedHash;
        /** COPY为读取原文件；SUPPLEMENT为人工补充正式原件，不宣称字节一致。 */
        private String legacyMode;
        /** 另建专用SOP时的来源版本；仅CREATE使用，批准信息不继承。 */
        private String derivedFromVersionId;
    }

    /** 绑定命令只接受真实稳定路线节点。 */
    @Data
    @Accessors(chain = true)
    public static class BindCommand {
        /** 永久请求号。 */
        private String requestId;
        /** BIND或UNBIND。 */
        private String action;
        /** 工艺路线编码。 */
        private String routeCode;
        /** 稳定路线节点编码，不允许以重复主工序猜测。 */
        private String routeNodeCode;
        /** 主工序编码，用于核对矛盾输入。 */
        private String processCode;
        /** 精确正式版本ID；解绑仍须提供原绑定版本。 */
        private String versionId;
        /** 首次绑定填ABSENT，其余填读到的bindingHash。 */
        private String expectedHash;
        /** 绑定、替换或解绑原因。 */
        private String reason;
    }

    /** 经当前真实用户工厂授权的固定查询作用域。 */
    @Data
    @Accessors(chain = true)
    public static class SearchQuery {
        /** 必须与认证上下文工厂一致。 */
        private String factoryCode;
        /** 编号或名称关键字。 */
        private String keyword;
        /** 默认第1页。 */
        private long current = 1;
        /** 默认10，最大50。 */
        private long size = 10;
    }

    /** 精确版本核验，普通退役仍可核原冻结版，安全撤回明确不可开工。 */
    @Data
    @Accessors(chain = true)
    public static class VerifyQuery {
        /** 必须与认证上下文工厂一致。 */
        private String factoryCode;
        /** 不接受“最新版”替代精确版。 */
        private String versionId;
        /** 原冻结内容指纹。 */
        private String contentHash;
    }

    /** 稳定路线节点身份，工序不是节点身份。 */
    @Data
    @Accessors(chain = true)
    public static class Node {
        /** 工艺路线编码。 */
        private String routeCode;
        /** 路线节点编码。 */
        private String routeNodeCode;
        /** 主工序编码。 */
        private String processCode;
    }

    /** 新下达批量解析，必须完整返回全部指定节点，不分页截断。 */
    @Data
    @Accessors(chain = true)
    public static class ResolveQuery {
        /** 当前授权工厂。 */
        private String factoryCode;
        /** 1至200个不重复的真实路线节点。 */
        private List<Node> nodes = new ArrayList<>();
    }

    /** 可引用精确源；不返回私有存储路径。 */
    @Data
    @Accessors(chain = true)
    public static class Source {
        /** 固定FORMAL_SOP_VERSION。 */
        private String sourceType = "FORMAL_SOP_VERSION";
        /** 内容合同版本。 */
        private String schemaVersion = "FORMAL_SOP_V1";
        /** 来源租户标识。 */
        private String tenantId;
        /** 来源工厂。 */
        private String factoryCode;
        /** 版本主键。 */
        private String versionId;
        /** 稳定编号。 */
        private String sopNo;
        /** 名称。 */
        private String name;
        /** 业务版本号。 */
        private Integer versionNo;
        /** 当前生命周期状态。 */
        private String status;
        /** 精确内容指纹。 */
        private String contentHash;
        /** 精确原件标识。 */
        private String originalId;
        /** 原件字节SHA256。 */
        private String originalHash;
        /** 原件真实字节数（文本长整型）。 */
        private String originalSize;
        /** 原件媒体类型。 */
        private String mediaType;
        /** 编制摘要，与原文件内容区别。 */
        private String description;
        /** 已批准适用限制。 */
        private String applicability;
        /** 批准人实名标识。 */
        private String approvedBy;
        /** 批准时刻。 */
        private LocalDateTime approvedAt;
        /** 正式生效时刻。 */
        private LocalDateTime effectiveFrom;
        /** 退役或安全撤回依据。 */
        private String withdrawalReason;
        /** 当前是否允许新下达。 */
        private boolean issueAllowed;
        /** 原冻结版是否允许新开工（仍须由MES核其他开工门）。 */
        private boolean frozenStartAllowed;
        /** 授权固定下载路径，不是自由URL。 */
        private String originalDownloadPath;
    }

    /** 精确核验结果，内容或原件损坏时失败而非给出批准证明。 */
    @Data
    @Accessors(chain = true)
    public static class Verification {
        /** 调用方冻结hash与正式源一致。 */
        private boolean matched;
        /** 精确源当前状态与原证明。 */
        private Source source;
        /** 可读核验说明。 */
        private String message;
    }

    /** 每个节点完整的正式SOP绑定清单；无绑定不冒充有正式SOP。 */
    @Data
    @Accessors(chain = true)
    public static class NodeSources {
        /** 已验证的稳定节点身份。 */
        private Node node;
        /** 所有生效正式绑定，按SOP编号稳定排序。 */
        private List<Source> sources = new ArrayList<>();
        /** 无正式绑定等明确缺口。 */
        private List<String> gaps = new ArrayList<>();
    }

    /** 完整下达取证清单，manifestHash不包含观察时间。 */
    @Data
    @Accessors(chain = true)
    public static class Manifest {
        /** 固定FORMAL_SOP_MANIFEST_V1。 */
        private String schemaVersion = "FORMAL_SOP_MANIFEST_V1";
        /** 来源租户。 */
        private String tenantId;
        /** 来源工厂。 */
        private String factoryCode;
        /** 完整节点清单，不静默截断。 */
        private List<NodeSources> nodes = new ArrayList<>();
        /** 稳定取证指纹。 */
        private String manifestHash;
        /** 已完成所有节点与绑定核验。 */
        private boolean complete;
    }

    /** 永久操作回执，已执行事实不随后续状态改变。 */
    @Data
    @Accessors(chain = true)
    public static class Receipt {
        /** 原永久请求号。 */
        private String requestId;
        /** 原动作。 */
        private String action;
        /** 原命令内容指纹。 */
        private String commandHash;
        /** 版本、原件或绑定标识。 */
        private String targetId;
        /** 操作后状态。 */
        private String status;
        /** 操作后修订/绑定/原件指纹。 */
        private String resultHash;
        /** 原操作时刻。 */
        private LocalDateTime operatedAt;
    }
}
