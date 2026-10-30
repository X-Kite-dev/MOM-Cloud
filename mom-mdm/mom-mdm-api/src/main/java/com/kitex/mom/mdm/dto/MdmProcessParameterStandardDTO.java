package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmProcessParameterItemEntity;
import com.kitex.mom.mdm.entity.MdmProcessParameterStandardEntity;
import com.kitex.mom.mdm.entity.MdmProcessParameterStandardLogEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工艺参数标准的维护、发布和工单冻结契约。
 */
public final class MdmProcessParameterStandardDTO {

    /**
     * 原主工序内容协议；数据库历史NULL与此值同义。
     */
    public static final String LEGACY_PROCESS_V1 = "LEGACY_PROCESS_V1";

    /**
     * 节点化标准内容协议，独立于业务versionNo。
     */
    public static final String NODE_V2 = "NODE_V2";

    /**
     * 新工单冻结协议，保留源内容协议和精确路线节点。
     */
    public static final String ROUTE_NODE_V2 = "ROUTE_NODE_V2";

    private MdmProcessParameterStandardDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class SaveCommand {
        private Long id;
        @Schema(description = "新页面填NODE_V2；旧请求缺省仅允许唯一工序桥接")
        @Size(max = 40)
        private String contentSchemaVersion;
        @NotBlank
        @Size(max = 100)
        private String standardNo;
        @NotBlank
        @Size(max = 200)
        private String standardName;
        @NotNull
        private Integer versionNo;
        @NotNull
        private Long processRouteId;
        @Size(max = 500)
        private String remark;
        @Valid
        @NotEmpty
        @Size(max = 500)
        private List<ItemInput> items = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class ItemInput {
        @Schema(description = "真实路线节点码；旧请求缺省时主工序必须在该路线唯一")
        @Size(max = 64)
        private String routeNodeCode;
        @NotBlank
        @Size(max = 100)
        private String processCode;
        @NotBlank
        @Size(max = 100)
        private String identifier;
        @NotBlank
        @Size(max = 200)
        private String parameterName;
        @NotBlank
        @Size(max = 30)
        private String dataType;
        @Size(max = 50)
        private String unit;
        private BigDecimal targetValue;
        private BigDecimal lowerLimit;
        private BigDecimal upperLimit;
        private BigDecimal warningLowerLimit;
        private BigDecimal warningUpperLimit;
        private Boolean required;
        @Size(max = 20)
        private String aggregationMode;
        private Integer minimumSampleCount;
        private BigDecimal maximumNonGoodRatePct;
        private Integer continuousDeviationSeconds;
        @Size(max = 500)
        private String remark;
    }

    @Data
    @Accessors(chain = true)
    public static class ActionCommand {
        @NotNull
        private Long id;
        @NotBlank
        @Size(max = 20)
        private String action;
        @Size(max = 500)
        private String reason;
        private LocalDateTime effectiveTime;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        private MdmProcessParameterStandardEntity standard;
        private List<MdmProcessParameterItemEntity> items = new ArrayList<>();
        private List<MdmProcessParameterStandardLogEntity> logs = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES下达工单时解析已发布工艺参数标准")
    public static class ResolveQuery {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;
        @NotNull
        private Long processRouteId;
        @NotBlank
        @Size(max = 100)
        private String routeCode;
        @Size(max = 500)
        private List<String> processCodes = new ArrayList<>();
        @Schema(description = "节点冻结入口必填的真实路线节点列表")
        @Size(max = 500)
        private List<String> routeNodeCodes = new ArrayList<>();
        private LocalDateTime effectiveTime;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "可被MES冻结的已发布工艺参数标准")
    public static class FrozenCatalog {
        @Schema(description = "冻结协议；新节点入口固定为ROUTE_NODE_V2")
        private String snapshotSchemaVersion;
        @Schema(description = "源标准内容协议：LEGACY_PROCESS_V1或NODE_V2")
        private String contentSchemaVersion;
        @Schema(description = "全部请求节点与真实主工序的对应关系；无参数标准也返回，不允许消费者猜测")
        private Map<String, String> routeNodeProcesses = new LinkedHashMap<>();
        private Boolean available;
        private Long standardId;
        private String standardNo;
        private String standardName;
        private Integer versionNo;
        private Long processRouteId;
        private String routeCode;
        private String routeName;
        private LocalDateTime effectiveTime;
        private LocalDateTime releasedTime;
        private String contentFingerprint;
        private LocalDateTime resolvedAt;
        private List<FrozenParameter> parameters = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class FrozenParameter {
        private Long sourceItemId;
        @Schema(description = "本次冻结的精确路线节点，不回写历史标准")
        private String routeNodeCode;
        private String processCode;
        private String processName;
        private String identifier;
        private String parameterName;
        private String dataType;
        private String unit;
        private BigDecimal targetValue;
        private BigDecimal lowerLimit;
        private BigDecimal upperLimit;
        private BigDecimal warningLowerLimit;
        private BigDecimal warningUpperLimit;
        private Boolean required;
        private String aggregationMode;
        private Integer minimumSampleCount;
        private BigDecimal maximumNonGoodRatePct;
        private Integer continuousDeviationSeconds;
    }
}
