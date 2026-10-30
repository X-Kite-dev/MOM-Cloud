package com.kitex.mom.mdm.agent.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 正式工艺参数知识的只读跨服务合同；不暴露实体、审批人员或可编辑正文。
 * 所有业务主键使用十进制字符串，所有十进制数使用字符串，避免浏览器精度损失。
 * 响应时间显式使用ISO纳秒格式、可空字段显式保留，不受业务服务公共Jackson默认格式影响。
 */
public final class MdmProcessKnowledgeDTO {

    private MdmProcessKnowledgeDTO() {
    }

    /**
     * 有界分页摘要查询；terms按OR匹配，空条件只查询当前授权工厂的正式源。
     */
    public record SearchQuery(
            @NotBlank @Size(max = 100) String factoryCode,
            @Size(max = 100) String keyword,
            @Size(max = 12) List<@NotBlank @Size(max = 100) String> terms,
            @Size(max = 100) String routeCode,
            @Size(max = 100) String processCode,
            @Min(1) @Max(1_000_000) Integer current,
            @Min(1) @Max(50) Integer size
    ) {
    }

    /**
     * 精确版本核验；不能自动回退旧版或替换成最新版本。
     */
    public record VerifyQuery(
            @NotBlank @Size(max = 100) String factoryCode,
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String standardId,
            @NotNull @Min(1) Integer versionNo,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedFingerprint
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourcePage(
            long current,
            long size,
            long total,
            List<SourceSummary> records,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS") LocalDateTime observedAt,
            List<String> dataGaps
    ) {
    }

    /**
     * 摘要是发现候选的元数据；使用参数正文前仍须精确核验。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceSummary(
            String sourceSystem,
            String sourceType,
            String tenantId,
            String factoryCode,
            String standardId,
            String standardNo,
            String standardName,
            int versionNo,
            String processRouteId,
            String routeCode,
            String routeName,
            String status,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS") LocalDateTime effectiveTime,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS") LocalDateTime releasedTime,
            String contentFingerprint,
            int itemCount,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS") LocalDateTime observedAt
    ) {
    }

    /**
     * 不可用时source必须为null，正文、节点、关系及引用全部为空。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceDetail(
            boolean available,
            SourceSummary source,
            List<SourceParameter> parameters,
            List<SourceNode> nodes,
            List<SourceRelation> relations,
            List<String> evidenceRefs,
            List<String> dataGaps,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS") LocalDateTime observedAt
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceParameter(
            String sourceItemId,
            String processCode,
            String processName,
            String identifier,
            String parameterName,
            String dataType,
            String unit,
            String targetValue,
            String lowerLimit,
            String upperLimit,
            String warningLowerLimit,
            String warningUpperLimit,
            boolean required,
            String aggregationMode,
            int minimumSampleCount,
            String maximumNonGoodRatePct,
            int continuousDeviationSeconds
    ) {
    }

    /**
     * 工序节点身份仅为路线内编码，objectId为空，不冒充全局工序主键。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceNode(
            String id,
            String type,
            String label,
            String objectId,
            String objectCode
    ) {
    }

    /**
     * 关系由核验后的实际结构生成，引用绑定租户、工厂、标准版本和内容指纹。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceRelation(
            String fromId,
            String toId,
            String type,
            String evidenceRef
    ) {
    }
}
