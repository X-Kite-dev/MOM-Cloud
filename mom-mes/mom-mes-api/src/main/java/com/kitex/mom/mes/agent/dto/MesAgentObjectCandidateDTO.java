package com.kitex.mom.mes.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 当前实名工厂的生产工单只读候选合同；候选选择不代表后续运行授权。
 */
public final class MesAgentObjectCandidateDTO {
    private MesAgentObjectCandidateDTO() {
    }

    /**
     * 仅接受当前工厂和有界页码；关键词与精确原编号互斥，租户从实名上下文取得。
     */
    @Schema(description = "智能体真实业务对象候选查询")
    public record Query(
            @NotBlank @Size(max = 100) @Schema(description = "必须严格等于当前实际授权工厂") String factoryCode,
            @Size(max = 100) @Schema(description = "可空查询词，禁止控制字符，查询时允许去除边界空白") String keyword,
            @Min(1) @Max(100000) @Schema(description = "页码，未提供时为1") Integer current,
            @Min(1) @Max(50) @Schema(description = "页大小，未提供时为10") Integer size,
            @Size(max = 100) @Schema(description = "可选精确原编号，与keyword互斥；不修剪、不模糊匹配") String exactBusinessNo) {
        /** 保留旧普通候选调用。 */
        public Query(String factoryCode, String keyword, Integer current, Integer size) {
            this(factoryCode, keyword, current, size, null);
        }
    }

    /**
     * 总数使用十进制文本，页码和页大小保持JSON数字；记录和总数属于同一只读快照。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "当前租户工厂内真实对象候选页")
    public record CandidatePage(
            @Schema(description = "实名租户ID十进制原文") String tenantId,
            @Schema(description = "已核验READ授权的当前工厂") String factoryCode,
            @Schema(description = "固定业务类型") String businessType,
            @Schema(description = "实际页码，JSON数字") Integer current,
            @Schema(description = "实际页大小，JSON数字") Integer size,
            @Schema(description = "匹配总数的非负十进制文本") String total,
            @Schema(description = "有界身份摘要，坏行保留并明确不可选") List<Candidate> records,
            @Schema(description = "精确模式回显原编号，普通模式为null；用于拒绝未实现精确合同的旧服务") String exactBusinessNo) {
        /** 兼容旧普通分页投影。 */
        public CandidatePage(String tenantId, String factoryCode, String businessType, Integer current,
                             Integer size, String total, List<Candidate> records) {
            this(tenantId, factoryCode, businessType, current, size, total, records, null);
        }
    }

    /**
     * 只投影原身份和小摘要，不加载业务大JSON、审批或凭据。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "真实对象只读身份摘要")
    public record Candidate(
            @Schema(description = "正Long主键十进制原文") String id,
            @Schema(description = "原业务编号，原空值保持null；含控制字符或超500字时为null，非法号不可选") String businessNo,
            @Schema(description = "物料名称，可空，最多500字") String label,
            @Schema(description = "原保存状态，可空，最多100字") String status,
            @Schema(description = "物料编码，可空，最多500字") String description,
            @Schema(description = "原计划开始时间，可空，最多40字") String plannedStart,
            @Schema(description = "原计划结束时间，可空，最多40字") String plannedEnd,
            @Schema(description = "仅原号有效且当前租户工厂未删除记录内无重号时可选") boolean selectable,
            @Schema(description = "不可选时的安全中文原因，最多200字；可选时为null") String unavailableReason) {
    }
}
