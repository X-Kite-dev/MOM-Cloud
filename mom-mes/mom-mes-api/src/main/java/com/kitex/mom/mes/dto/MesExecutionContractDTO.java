package com.kitex.mom.mes.dto;

import com.kitex.mom.mdm.dto.MdmPhysicalTransformStandardDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 派工单冻结后的生产执行契约，供管理端、工站端和移动端统一渲染与校验。
 */
@Data
@Accessors(chain = true)
@Schema(description = "生产执行与报工契约")
public class MesExecutionContractDTO {

    @Schema(description = "模板ID；历史兼容策略为空")
    private Long templateId;
    private String templateCode;
    private String templateName;
    private Integer templateVersion;
    @Schema(description = "是否由派工时模板冻结；false表示兼容旧工艺配置")
    private Boolean templateFrozen;

    private String arrangeCode;

    /** 原生动态契约当前实际父任务，旧契约不补值、不参与历史JSON重算。 */
    @Schema(description = "原生动态策略当前工序任务号", accessMode = Schema.AccessMode.READ_ONLY)
    private String produceTaskCode;

    /** 只从原生派工冻结读取，不能用今日模板推断历史。 */
    @Schema(description = "原生动态策略当前稳定节点", accessMode = Schema.AccessMode.READ_ONLY)
    private String sourceRouteNodeCode;

    /** 与主工序编码独立的所属工程路线。 */
    @Schema(description = "原生动态策略所属工艺路线", accessMode = Schema.AccessMode.READ_ONLY)
    private String processRouteCode;
    private String reportMode;
    private String reportModeName;
    private String objectGranularity;
    private String quantityPolicy;
    private String identifierPolicy;

    @Schema(description = "标识解析方式：CANONICAL_ONLY/ALLOW_MAPPING/MAPPING_REQUIRED")
    private String identifierResolveMode;

    @Schema(description = "允许使用的映射标识类型")
    private List<String> allowedIdentifierTypes;

    /**
     * 旧快照默认NONE；非NONE必须以派工冻结的真实节点与组定义执行。
     */
    @Schema(description = "派工冻结的动态外码节点策略；缺字段默认NONE，不读取当前模板反推")
    private MesIdentifierNodePolicyDTO identifierNodePolicy = new MesIdentifierNodePolicyDTO();

    /**
     * 实际派工原转换配置及版本化指纹；旧快照缺字段为NONE，不从当前模板补造。
     */
    @Schema(description = "派工冻结实物转换配置证据；不代表转换执行许可")
    private MesPhysicalTransformPolicyContractDTO physicalTransformPolicy;

    /**
     * 新派工时精确正式转换工程原正文及发布依据；历史缺失不查询当前标准补造。
     */
    @Schema(description = "派工冻结正式转换工程版本；不等于库存/母件位置执行资格")
    private MdmPhysicalTransformStandardDTO.Frozen physicalTransformStandard;

    private List<String> allowedTriggers = new ArrayList<>();

    private Boolean partialReportAllowed;
    private Boolean overReportAllowed;
    private BigDecimal overReportRate;
    private BigDecimal maxSingleQuantity;
    private Boolean predecessorRequired;
    private Boolean materialReadyRequired;
    private Boolean qualityGateRequired;
    private Boolean worksiteRequired;
    private Boolean deviceRequired;
    private Boolean toolingRequired;
    private Boolean mobileAllowed;
    private Boolean stationAllowed;

    @Schema(description = "派工数量")
    private BigDecimal noticeQuantity;
    @Schema(description = "累计已报数量")
    private BigDecimal completedQuantity;
    @Schema(description = "按冻结策略计算的剩余可报数量")
    private BigDecimal remainingQuantity;
    @Schema(description = "前端可直接展示的控制说明")
    private List<String> controlMessages = new ArrayList<>();
}
