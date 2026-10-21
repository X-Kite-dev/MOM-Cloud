package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 原号结果只输出文本ID/数量及完整原字符串，不以ABSENT授权换号。
 */
public final class MesProductionNgCaptureDTO {
    private MesProductionNgCaptureDTO() {
    }

    /**
     * 当前派工的只读准备信息，不是库存预留或执行许可；提交时重新校验全部生产门。
     */
    @Schema(description = "生产不良采集准备信息")
    public record Preparation(
            @Schema(description = "实际租户") String tenantId,
            @Schema(description = "实际工厂") String factoryCode,
            @Schema(description = "当前认证用户") String actorId,
            @Schema(description = "实际派工") String arrangeCode,
            @Schema(description = "生产工单") String taskCode,
            @Schema(description = "工序任务") String produceTaskCode,
            @Schema(description = "生产物料") String materialCode,
            @Schema(description = "物料名称") String materialName,
            @Schema(description = "实际基本单位，不默认piece") String unit,
            @Schema(description = "原派工报工对象模式") String reportMode,
            @Schema(description = "原派工接收时确认的实际工站") String worksiteCode,
            @Schema(description = "可信路由指定的STATION或MOBILE") String executionChannel,
            @Schema(description = "该通道是否允许人工采集；不代表全部生产门通过") boolean channelAllowed,
            @Schema(description = "当前用户是否为派工指定执行人") boolean assignedWorker,
            @Schema(description = "当前模板与节点的限制说明") String restriction) {
    }

    @Schema(description = "本人生产不良采集永久原号结果")
    public record Receipt(
            @Schema(description = "原请求号") String requestId,
            @Schema(description = "ABSENT/ISOLATED/CANCELLED，不代表QMS处置完成") String state,
            @Schema(description = "实际租户") String tenantId,
            @Schema(description = "实际工厂") String factoryCode,
            @Schema(description = "首次实际操作人") String actorId,
            @Schema(description = "采集主键") String captureId,
            @Schema(description = "真实不良报告主键") String sourceUnqualifiedReportId,
            @Schema(description = "原始隔离库存主键") String stockId,
            @Schema(description = "原始实际入库流水主键") String inflowId,
            @Schema(description = "独立质量案件主键") String caseId,
            @Schema(description = "请求版本") String commandVersion,
            @Schema(description = "完整请求原文") String commandSnapshot,
            @Schema(description = "原请求SHA") String commandFingerprint,
            @Schema(description = "证据版本") String evidenceVersion,
            @Schema(description = "完整实际采集证据") String evidenceSnapshot,
            @Schema(description = "原证据SHA") String evidenceFingerprint,
            @Schema(description = "首次实际入口STATION/MOBILE；历史、未执行或作废为NULL，不回填历史") String executionChannel,
            @Schema(description = "独立双标识版本；旧历史/未执行/作废为空") String identificationVersion,
            @Schema(description = "双标识原文；不改变原命令和隔离证据") String identificationSnapshot,
            @Schema(description = "独立双标识SHA-256") String identificationFingerprint) {
    }
}
