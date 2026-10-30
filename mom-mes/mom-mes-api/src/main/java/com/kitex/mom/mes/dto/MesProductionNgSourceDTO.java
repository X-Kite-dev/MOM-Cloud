package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 真实不良报工的原计量回执。长整数和数量按文本交付，不允许端侧转换为浮点。
 * 来源冻结不代表库存化、质量放行、耗料已分配或实物转换许可。
 */
@Data
@Schema(description = "真实不良报工原计量与来源回执")
public class MesProductionNgSourceDTO {
    /**
     * 独立标识证据，不扩展历史SOURCE_V1正文；三个字段全NULL表示旧历史。
     */
    @Schema(description = "原不良双标识证据版本，历史为空")
    private String identificationVersion;
    /**
     * 固定12字段原文，包含文本映射ID/版本与原始、规范标识。
     */
    @Schema(description = "原不良双标识完整证据")
    private String identificationSnapshot;
    /**
     * SHA必须与上述完整原字符串及独立版本一致。
     */
    @Schema(description = "原不良双标识SHA-256")
    private String identificationFingerprint;
    /**
     * 后续真实采集事实只附链接，不覆盖原SOURCE_V1的UNSTOCKED字符串/SHA。
     */
    private InventoryLink inventoryCapture;
    /**
     * 独立耗料恢复事实；原FEED模式/原文保留，不覆盖为NONE。
     */
    private MesProductionMaterialReversalDTO.Receipt materialReversal;
    /**
     * 原不良报告主键，与合格报告ID域明确分开。
     */
    @Schema(description = "原不良报告ID")
    private String sourceUnqualifiedReportId;
    /**
     * 原上报请求号，只查询原结果，不再次上报。
     */
    @Schema(description = "原不良上报请求号")
    private String requestId;
    /**
     * 原数据库接受时刻；与设备或页面输入时间分开。
     */
    @Schema(description = "服务器接受时刻，旧历史缺失为空")
    private String timingAcceptedAt;
    /**
     * 原报工时冻结的时间规则/来源/窗口，不按今天的时间或工艺重新判定。
     */
    @Schema(description = "不良报工原时间校验证据JSON")
    private String timingEvidenceJson;
    /**
     * 当前已认证租户，不采信页面传入归属。
     */
    @Schema(description = "实际租户ID")
    private String tenantId;
    /**
     * 原报告所属实际工厂。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;
    /**
     * 原派工编码。
     */
    @Schema(description = "原派工单号")
    private String arrangeCode;
    /**
     * 原报告物料，不以当前主数据名称替换原事实。
     */
    @Schema(description = "原物料编码")
    private String materialCode;
    /**
     * 原不良数量文本；历史缺量保持空值，不能默认为零。
     */
    @Schema(description = "原不良数量文本")
    private String quantity;
    /**
     * 原单位；旧历史未冻结时为空。
     */
    @Schema(description = "原计量单位")
    private String unit;
    /**
     * 原规范报工模式；不由当前派工重算。
     */
    @Schema(description = "原报工模式")
    private String reportMode;
    /**
     * FROZEN_UNSTOCKED/FROZEN_ISOLATED/LEGACY_UNPROVEN，均不是质量放行或可用量。
     */
    @Schema(description = "原来源证明状态")
    private String sourceState;
    /**
     * 原独立来源版本，历史为空。
     */
    @Schema(description = "原来源版本")
    private String sourceVersion;
    /**
     * 原完整证据SHA，旧历史不生成假的SHA。
     */
    @Schema(description = "原来源证据SHA-256")
    private String sourceFingerprint;
    /**
     * 原证据完整字符串，供授权追溯；不作为客户端写许可。
     */
    @Schema(description = "原完整来源证据")
    private String sourceSnapshot;
    /**
     * 仅根据实际永久采集链接说明是否曾初始库存化，不代表当前剩余量或放行。
     */
    @Schema(description = "是否存在实际初始隔离采集；不代表库存可用")
    private boolean inventoryRecorded;
    /**
     * 原真实耗用选择；声明不能当成零耗料证明。
     */
    @Schema(description = "原耗料方式：NONE声明或FEED真实投料耗用")
    private String materialAccountingMode;
    /**
     * FEED原永久主账ID及全文/SHA；NONE为空，不补造历史。
     */
    private String materialOperationId;
    private String materialAccountingVersion;
    private String materialAccountingFingerprint;
    private String materialAccountingSnapshot;

    public record InventoryLink(
            @Schema(description = "真实初始采集账主键") String captureId,
            @Schema(description = "隔离根库存主键") String stockId,
            @Schema(description = "真实初始入库流水主键") String inflowId,
            @Schema(description = "独立生产NG质量案件主键") String caseId,
            @Schema(description = "初始库存采集证据版本") String evidenceVersion,
            @Schema(description = "原采集证据SHA") String evidenceFingerprint) {
    }
}
