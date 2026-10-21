package com.kitex.mom.mes.agent.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单下达冻结知识的独立只读合同；不以现行MDM主数据补写历史。
 * 业务ID和十进制值均为文本，时间固定ISO纳秒，null/false均显式保留。
 */
public final class MesWorkOrderKnowledgeDTO {
    private MesWorkOrderKnowledgeDTO() {
    }

    /**
     * 只读工单候选，不携带任何快照正文。
     *
     * @param factoryCode 必须与当前实名实际工厂一致
     * @param keyword     工单编码或物料关键词，不超过100字
     * @param current     从1开始的页码，空值使用第1页
     * @param size        每页1至50条，空值使用20条
     */
    public record CandidateQuery(@NotBlank @Size(max = 100) String factoryCode,
                                 @Size(max = 100) String keyword,
                                 @Min(1) @Max(1_000_000) Long current,
                                 @Min(1) @Max(50) Long size) {
    }

    /**
     * 只接受精确工单号；租户始终由实名身份决定。
     *
     * @param factoryCode                   当前实际工厂，不允许扩大查询范围
     * @param taskCode                      当前工厂内唯一的完整工单编码
     * @param acceptedSnapshotSchemaVersion 缺省只接受V1；节点消费者须显式声明ROUTE_NODE_V2
     */
    public record Query(@NotBlank @Size(max = 100) String factoryCode,
                        @NotBlank @Size(max = 100) String taskCode,
                        @Size(max = 40) String acceptedSnapshotSchemaVersion) {
        /**
         * 保留历史Java调用的V1能力，不隐式升级其响应合同。
         */
        public Query(String factoryCode, String taskCode) {
            this(factoryCode, taskCode, null);
        }
    }

    /**
     * 实际服务器分页结果，不代表候选已经有可用冻结知识。
     *
     * @param current    请求页码
     * @param size       请求页容量
     * @param total      当前授权范围匹配总数
     * @param records    仅工单身份头，不含快照正文
     * @param observedAt 本次只读快照观察时间，固定ISO纳秒
     * @param dataGaps   目录适用边界及缺口说明
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CandidatePage(long current, long size, long total, List<WorkOrder> records,
                                @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS") LocalDateTime observedAt,
                                List<String> dataGaps) {
    }

    /**
     * 工单自身保存的身份头；路线编码不冒充批准路线版本。
     *
     * @param taskId           MES工单主键，十进制字符串
     * @param taskCode         原工单编码
     * @param status           原业务状态，不由知识投影改写
     * @param materialCode     工单保存的物料编码
     * @param materialName     工单保存的物料名称
     * @param bomId            原BOM引用主键，缺失时为空
     * @param bomVersion       工单保存的BOM版号，非完整组件谱系证明
     * @param processRouteId   原路线引用主键，缺失时为空
     * @param processRouteCode 工单保存的路线编码
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkOrder(String taskId, String taskCode, String status, String materialCode,
                            String materialName, String bomId, String bomVersion,
                            String processRouteId, String processRouteCode) {
    }

    /**
     * available仅表示存在可用冻结工艺正文，不表示SOP批准字节或完整BOM谱系已证明。
     * INVALID_SNAPSHOT/OVERSIZED/NOT_FROZEN时workOrder及正文/关系全部清空。
     *
     * @param schemaVersion      历史工序合同为1；明确协商后的节点合同为2
     * @param sourceSystem       固定来源MES
     * @param sourceType         固定来源类型WORK_ORDER_FROZEN_KNOWLEDGE
     * @param tenantId           实名租户ID，十进制字符串
     * @param factoryCode        已核实际READ授权的工厂编码
     * @param taskCode           本次精确请求的工单编码
     * @param available          是否可安全展示原冻结路线正文
     * @param applicabilityState 冻结可用、局部缺口、未冻结、损坏或超限状态
     * @param workOrder          可用时的工单身份头，不可用时为空
     * @param snapshots          三类原快照证明，区分持久校验与本次观察摘要
     * @param processes          原冻结工序与SOP引用，不查询现行主数据补齐
     * @param standard           原参数标准冻结投影，未保存时为空
     * @param nodes              仅由原冻结结构证明的对象节点
     * @param relations          仅由原冻结结构证明的有向关系
     * @param evidenceRefs       该工单范围内精确来源引用
     * @param dataGaps           完整有界缺口说明，不把缺失解释为零或合格
     * @param observedAt         本次独立只读快照的观察时间
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Evidence(int schemaVersion, String sourceSystem, String sourceType, String tenantId,
                           String factoryCode, String taskCode, boolean available, String applicabilityState,
                           WorkOrder workOrder, List<SnapshotProof> snapshots, List<Process> processes,
                           Standard standard, List<Node> nodes, List<Relation> relations,
                           List<String> evidenceRefs, List<String> dataGaps,
                           @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS") LocalDateTime observedAt) {
    }

    /**
     * OBSERVED_SHA256仅证明本次读取原字节摘要；PERSISTED_SHA256核原保存摘要。
     *
     * @param kind           PROCESS_ROUTE、BOM或PROCESS_PARAMETER_STANDARD
     * @param state          PRESENT或MISSING
     * @param hash           原快照UTF8摘要；缺失时为空
     * @param integrityLevel OBSERVED_SHA256、PERSISTED_SHA256或NONE
     * @param evidenceRef    原工单范围及快照摘要组成的精确引用；缺失时为空
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SnapshotProof(String kind, String state, String hash, String integrityLevel,
                                String evidenceRef) {
    }

    /**
     * SOP的null/空/引用分别为LEGACY_UNDECLARED/NO_REFERENCE/REFERENCE_ONLY。
     *
     * @param processCode   原工序编码，仅在该工单冻结路线内标识
     * @param processName   原名称，旧快照未声明时为空
     * @param order         原顺序，未声明时为空；不推断并行拓扑
     * @param sopState      SOP清单的声明及证明状态
     * @param sopReferences 原SOP文件引用，不含文件正文或访问地址
     * @param evidenceRef   证明该工序的原路线快照引用
     * @param routeNodeCode V2原冻结节点，V1不补写也不输出此扩展字段
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Process(String processCode, String processName, Integer order, String sopState,
                          List<SopReference> sopReferences, String evidenceRef,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String routeNodeCode) {
    }

    /**
     * 只证明原引用清单，不公开URL/存储路径，也不证明原文件批准版及字节。
     *
     * @param sourceId      原引用保存的业务ID，未声明时为空
     * @param name          原引用名称
     * @param referenceHash SOP引用JSON摘要，不是文件字节摘要
     * @param evidenceRef   原路线快照及引用摘要组成的精确引用
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SopReference(String sourceId, String name, String referenceHash, String evidenceRef) {
    }

    /**
     * MES原FrozenCatalog镜像；available=false是当时明确无正式标准，不能回查今日标准。
     *
     * @param available             当时是否存在正式参数标准；可用标准也可能缺所选工序参数
     * @param standardId            原标准主键字符串，不可用时为空
     * @param standardNo            原标准编号，不可用时为空
     * @param standardName          原标准名称，不可用时为空
     * @param versionNo             原标准正整数版号，不可用时为空
     * @param processRouteId        原标准关联的路线主键字符串
     * @param routeCode             原标准关联的路线编码
     * @param routeName             原路线名称，允许未声明
     * @param effectiveTime         原标准生效时间
     * @param releasedTime          原标准发布时间
     * @param contentFingerprint    原MDM正式标准内容指纹，非MES快照字节摘要
     * @param resolvedAt            MES下达时解析标准的时间
     * @param parameters            当时所选工序参数，保持原十进制精度
     * @param dataGaps              当时解析及本次校验发现的参数缺口
     * @param snapshotSchemaVersion 节点冻结协议；V1省略以保留原响应合同
     * @param contentSchemaVersion  来源标准内容协议，与来源内容指纹共同保留
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Standard(boolean available, String standardId, String standardNo, String standardName,
                           Integer versionNo, String processRouteId, String routeCode, String routeName,
                           @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS") LocalDateTime effectiveTime,
                           @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS") LocalDateTime releasedTime,
                           String contentFingerprint,
                           @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS") LocalDateTime resolvedAt,
                           List<Parameter> parameters, List<String> dataGaps,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String snapshotSchemaVersion,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String contentSchemaVersion) {
    }

    /**
     * 规则保留来源原十进制精度，不经过double。
     *
     * @param sourceItemId               原标准参数主键字符串
     * @param processCode                原工序编码，须属于冻结路线
     * @param processName                原参数保存的工序名称
     * @param identifier                 工序内参数标识符
     * @param parameterName              参数名称
     * @param dataType                   原DECIMAL或INTEGER类型，不由投影推断
     * @param unit                       原计量单位，不做隐式换算
     * @param targetValue                原目标值字符串，未定义时为空
     * @param lowerLimit                 原控制下限字符串，未定义时为空
     * @param upperLimit                 原控制上限字符串，未定义时为空
     * @param warningLowerLimit          原警戒下限字符串，未定义时为空
     * @param warningUpperLimit          原警戒上限字符串，未定义时为空
     * @param required                   原必采规则
     * @param aggregationMode            原RANGE或AVERAGE聚合方式
     * @param minimumSampleCount         原最少有效采样数量
     * @param maximumNonGoodRatePct      原最大非GOOD百分比字符串
     * @param continuousDeviationSeconds 原允许连续偏差秒数
     * @param routeNodeCode              V2精确节点身份，V1省略；不从当前MDM补齐
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Parameter(String sourceItemId, String processCode, String processName, String identifier,
                            String parameterName, String dataType, String unit, String targetValue,
                            String lowerLimit, String upperLimit, String warningLowerLimit,
                            String warningUpperLimit, Boolean required, String aggregationMode,
                            Integer minimumSampleCount, String maximumNonGoodRatePct,
                            Integer continuousDeviationSeconds,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String routeNodeCode) {
    }

    /**
     * 工序及物料没有可靠全局ID时objectId为null，身份仅在该工单冻结结构内有效。
     *
     * @param id         该工单范围内稳定节点键，不是全局实体合并依据
     * @param type       固定合同允许的对象类型
     * @param label      来源标签，不承载HTML或任意URL
     * @param objectId   原可靠业务主键字符串，没有时为空
     * @param objectCode 原对象编码或版号，没有时为空
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Node(String id, String type, String label, String objectId, String objectCode) {
    }

    /**
     * 关系只由该工单原冻结结构派生，每条绑定本次原快照证据引用。
     *
     * @param fromId      当前投影内起点节点键
     * @param toId        当前投影内终点节点键
     * @param type        固定合同允许的关系类型，不接受模型自创关系
     * @param evidenceRef 证明本条关系的原快照精确引用
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Relation(String fromId, String toId, String type, String evidenceRef) {
    }
}
